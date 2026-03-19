package com.walletdomain.domain.model

import com.walletdomain.domain.event.DomainEvent
import com.walletdomain.domain.exception.DomainException
import java.time.Instant

data class Wallet(
    val id: WalletId = WalletId.generate(),
    val userId: UserId,
    val currency: Currency,
    val pockets: Map<PocketId, Pocket> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val active: Boolean = true,
) {
    val totalBalance: Money
        get() = pockets.values.filter { it.active }.fold(Money.zero(currency)) { acc, p -> acc + p.balance }

    val activePockets: List<Pocket>
        get() = pockets.values.filter { it.active }

    fun createPocket(name: String, allowedBenefits: Set<BenefitCategory>): Pair<Wallet, List<DomainEvent>> {
        requireActive()
        require(pockets.values.none { it.name == name && it.active }) { "A pocket named '$name' already exists" }
        val (pocket, events) = Pocket.create(id, name, allowedBenefits, currency)
        return copy(pockets = pockets + (pocket.id to pocket)) to events
    }

    fun creditPocket(pocketId: PocketId, amount: Money, source: CreditSource, reference: String): Pair<Wallet, List<DomainEvent>> {
        requireActive()
        val (updated, events) = requirePocket(pocketId).credit(amount, source, reference)
        return copy(pockets = pockets + (pocketId to updated)) to events
    }

    fun spend(pocketId: PocketId, amount: Money, merchant: String, category: BenefitCategory, reference: String): Pair<Wallet, List<DomainEvent>> {
        requireActive()
        val (updated, events) = requirePocket(pocketId).spend(amount, merchant, category, reference)
        return copy(pockets = pockets + (pocketId to updated)) to events
    }

    fun transferBetweenPockets(fromPocketId: PocketId, toPocketId: PocketId, amount: Money, reference: String): Pair<Wallet, List<DomainEvent>> {
        requireActive()
        require(fromPocketId != toPocketId) { "Cannot transfer to the same pocket" }
        // Use debitInternal — bypasses category guard since transfers are not benefit-category-scoped
        val (updatedFrom, debitEvents) = requirePocket(fromPocketId).debitInternal(amount, reference)
        val (updatedTo, creditEvents)  = requirePocket(toPocketId).credit(amount, CreditSource.TRANSFER, reference)
        val transferEvent = DomainEvent.PocketTransferExecuted(id, fromPocketId, toPocketId, amount, reference)
        return copy(pockets = pockets + (fromPocketId to updatedFrom) + (toPocketId to updatedTo)) to
            (debitEvents + creditEvents + transferEvent)
    }

    fun deactivatePocket(pocketId: PocketId): Pair<Wallet, List<DomainEvent>> {
        requireActive()
        val (updated, events) = requirePocket(pocketId).deactivate()
        return copy(pockets = pockets + (pocketId to updated)) to events
    }

    fun closeWallet(): Pair<Wallet, List<DomainEvent>> {
        requireActive()
        require(totalBalance.amount == 0L) { "Cannot close wallet with remaining balance: $totalBalance" }
        return copy(active = false) to listOf(DomainEvent.WalletClosed(id, userId))
    }

    fun getPocket(pocketId: PocketId): Pocket? = pockets[pocketId]
    fun getLedger(pocketId: PocketId): List<LedgerEntry> = pockets[pocketId]?.ledger ?: emptyList()

    private fun requireActive() { if (!active) throw DomainException.WalletInactive(id) }
    private fun requirePocket(id: PocketId) = pockets[id] ?: throw DomainException.PocketNotFound(id)

    companion object {
        fun create(userId: UserId, currency: Currency): Pair<Wallet, List<DomainEvent>> {
            val wallet = Wallet(userId = userId, currency = currency)
            return wallet to listOf(DomainEvent.WalletCreated(wallet.id, userId, currency))
        }
    }
}
