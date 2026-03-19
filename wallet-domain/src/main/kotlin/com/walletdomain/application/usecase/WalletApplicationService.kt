package com.walletdomain.application.usecase

import com.walletdomain.application.command.*
import com.walletdomain.application.query.*
import com.walletdomain.domain.event.DomainEvent
import com.walletdomain.domain.exception.DomainException
import com.walletdomain.domain.model.*
import com.walletdomain.domain.port.DomainEventPublisher
import com.walletdomain.domain.port.NoOpEventPublisher
import com.walletdomain.domain.port.WalletRepository
import com.walletdomain.domain.rules.engine.RulesEngine
import com.walletdomain.domain.rules.model.EngineResult
import com.walletdomain.domain.rules.model.RuleContext
import com.walletdomain.domain.rules.model.RuleDecision
import com.walletdomain.domain.rules.policy.GlobalPolicy
import com.walletdomain.domain.rules.policy.GlobalPolicyLoader
import com.walletdomain.domain.rules.policy.StaticGlobalPolicyLoader
import com.walletdomain.domain.rules.policy.WalletPolicy
import com.walletdomain.domain.rules.policy.WalletPolicyLoader

// ---------------------------------------------------------------------------
// WalletApplicationService — orchestrates domain + two-level rules engine.
//
// Rule evaluation order for every spend/transfer:
//   1. Global rules  (instance-wide, from GlobalPolicyLoader)
//   2. Wallet rules  (per-wallet, from WalletPolicyLoader)
//
// Both levels are concatenated into a single flat list.
// AND semantics: the first Deny from either level short-circuits everything.
// ---------------------------------------------------------------------------

class WalletApplicationService(
    private val walletRepository: WalletRepository,
    private val eventPublisher: DomainEventPublisher = NoOpEventPublisher,
    private val walletPolicyLoader: WalletPolicyLoader? = null,
    private val globalPolicyLoader: GlobalPolicyLoader = StaticGlobalPolicyLoader(),
) {
    private val engine = RulesEngine()

    // ------------------------------------------------------------------
    // Wallet
    // ------------------------------------------------------------------

    fun createWallet(command: CreateWalletCommand): WalletView {
        if (walletRepository.existsByUserId(command.userId))
            throw DomainException.WalletAlreadyExists(command.userId)
        val (wallet, events) = Wallet.create(command.userId, Currency.of(command.currencyCode))
        return walletRepository.save(wallet).also { eventPublisher.publishAll(events) }.toView()
    }

    fun getWallet(walletId: WalletId): WalletView = requireWallet(walletId).toView()

    fun getWalletByUser(userId: UserId): WalletView =
        (walletRepository.findByUserId(userId)
            ?: throw DomainException.WalletNotFound(WalletId())).toView()

    fun closeWallet(command: CloseWalletCommand): WalletView {
        val (updated, events) = requireWallet(command.walletId).closeWallet()
        return walletRepository.save(updated).also { eventPublisher.publishAll(events) }.toView()
    }

    // ------------------------------------------------------------------
    // Pockets
    // ------------------------------------------------------------------

    fun createPocket(command: CreatePocketCommand): PocketView {
        val wallet = requireWallet(command.walletId)
        val benefits = command.allowedBenefitCodes.map { BenefitCategory.of(it) }.toSet()
        val (updated, events) = wallet.createPocket(command.name, benefits)
        return walletRepository.save(updated)
            .also { eventPublisher.publishAll(events) }
            .pockets.values.first { it.name == command.name && it.active }.toView()
    }

    fun getPocket(walletId: WalletId, pocketId: PocketId): PocketView =
        (requireWallet(walletId).getPocket(pocketId)
            ?: throw DomainException.PocketNotFound(pocketId)).toView()

    fun deactivatePocket(command: DeactivatePocketCommand): PocketView {
        val (updated, events) = requireWallet(command.walletId).deactivatePocket(command.pocketId)
        return walletRepository.save(updated)
            .also { eventPublisher.publishAll(events) }
            .pockets[command.pocketId]!!.toView()
    }

    // ------------------------------------------------------------------
    // Credit — no rules, always permitted
    // ------------------------------------------------------------------

    fun creditPocket(command: CreditPocketCommand): PocketView {
        val wallet = requireWallet(command.walletId)
        val (updated, events) = wallet.creditPocket(
            command.pocketId, Money.of(command.amount, command.currencyCode),
            command.source, command.reference,
        )
        return walletRepository.save(updated)
            .also { eventPublisher.publishAll(events) }
            .pockets[command.pocketId]!!.toView()
    }

    // ------------------------------------------------------------------
    // Spend — global rules then wallet rules, all must pass
    // ------------------------------------------------------------------

    fun spend(command: SpendCommand): PocketView {
        val wallet  = requireWallet(command.walletId)
        val pocket  = wallet.getPocket(command.pocketId) ?: throw DomainException.PocketNotFound(command.pocketId)
        val amount  = Money.of(command.amount, command.currencyCode)
        val benefit = BenefitCategory.of(command.benefitCategoryCode)

        val ctx = RuleContext.SpendContext(
            wallet = wallet, pocket = pocket,
            requestedAmount = amount, merchant = command.merchant,
            benefitCategory = benefit, reference = command.reference,
        )

        // Concatenate: global rules run first, wallet rules run second
        val global = globalPolicyLoader.load()
        val wallet_p = loadWalletPolicy(wallet.id)
        val rules  = global.spendRules() + wallet_p.spendRules()

        val result = engine.evaluateSpend(ctx, rules)
        publishNotifications(wallet.id, result)

        return when (val d = result.decision) {
            is RuleDecision.Deny -> {
                eventPublisher.publish(DomainEvent.OperationRejectedByPolicy(
                    wallet.id, "SPEND", d.ruleName, d.reason, command.reference,
                ))
                throw DomainException.InvalidOperation("Spend rejected by [${d.ruleName}]: ${d.reason}")
            }

            is RuleDecision.SplitOverspend -> {
                var current = wallet
                if (d.primaryAmount.amount > 0) {
                    val (w, e) = current.spend(command.pocketId, d.primaryAmount, command.merchant, benefit, command.reference)
                    current = w; eventPublisher.publishAll(e)
                }
                val (afterCover, coverEvents) = current.spend(
                    d.coverPocketId, d.shortfall, command.merchant, benefit, "${command.reference}-SPLIT",
                )
                eventPublisher.publishAll(coverEvents)
                eventPublisher.publish(DomainEvent.OverspendSplitExecuted(
                    wallet.id, command.pocketId, d.coverPocketId,
                    d.primaryAmount, d.shortfall, command.merchant, command.reference,
                ))
                walletRepository.save(afterCover).pockets[command.pocketId]!!.toView()
            }

            else -> {
                val (updated, events) = wallet.spend(command.pocketId, amount, command.merchant, benefit, command.reference)
                walletRepository.save(updated).also { eventPublisher.publishAll(events) }
                    .pockets[command.pocketId]!!.toView()
            }
        }
    }

    // ------------------------------------------------------------------
    // Transfer — global rules then wallet rules, all must pass
    // ------------------------------------------------------------------

    fun transfer(command: TransferCommand): WalletView {
        val wallet = requireWallet(command.walletId)
        val from   = wallet.getPocket(command.fromPocketId) ?: throw DomainException.PocketNotFound(command.fromPocketId)
        val to     = wallet.getPocket(command.toPocketId)   ?: throw DomainException.PocketNotFound(command.toPocketId)
        val amount = Money.of(command.amount, command.currencyCode)

        val ctx = RuleContext.TransferContext(
            wallet = wallet, fromPocket = from, toPocket = to,
            requestedAmount = amount, reference = command.reference,
        )

        val global = globalPolicyLoader.load()
        val wallet_p = loadWalletPolicy(wallet.id)
        val rules  = global.transferRules() + wallet_p.transferRules()

        val result = engine.evaluateTransfer(ctx, rules)
        publishNotifications(wallet.id, result)

        return when (val d = result.decision) {
            is RuleDecision.Deny -> {
                eventPublisher.publish(DomainEvent.OperationRejectedByPolicy(
                    wallet.id, "TRANSFER", d.ruleName, d.reason, command.reference,
                ))
                throw DomainException.InvalidOperation("Transfer rejected by [${d.ruleName}]: ${d.reason}")
            }
            else -> {
                val (updated, events) = wallet.transferBetweenPockets(
                    command.fromPocketId, command.toPocketId, amount, command.reference,
                )
                walletRepository.save(updated).also { eventPublisher.publishAll(events) }.toView()
            }
        }
    }

    // ------------------------------------------------------------------
    // Ledger
    // ------------------------------------------------------------------

    fun getLedger(walletId: WalletId, pocketId: PocketId): LedgerView =
        requireWallet(walletId).getLedger(pocketId).toView(pocketId)

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun requireWallet(walletId: WalletId) =
        walletRepository.findById(walletId) ?: throw DomainException.WalletNotFound(walletId)

    private fun loadWalletPolicy(walletId: WalletId): WalletPolicy =
        walletPolicyLoader?.load(walletId) ?: WalletPolicy(walletId = walletId)

    private fun publishNotifications(walletId: WalletId, result: EngineResult) {
        result.notifications.forEach { n ->
            eventPublisher.publish(DomainEvent.RuleNotificationRaised(walletId, n.ruleName, n.reason, n.payload))
        }
    }
}
