package com.walletdomain.domain.rules

import com.walletdomain.application.command.*
import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.exception.DomainException
import com.walletdomain.domain.model.*
import com.walletdomain.domain.rules.engine.*
import com.walletdomain.domain.rules.model.*
import com.walletdomain.domain.rules.policy.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.*

// ---------------------------------------------------------------------------
// Unit tests — rules evaluated directly, no HTTP or Spring context
// ---------------------------------------------------------------------------

class RulesEngineTest {

    private val engine   = RulesEngine()
    private val walletId = WalletId.generate()
    private val pocketId = PocketId.generate()
    private val backupId = PocketId.generate()

    // ------------------------------------------------------------------
    // Helpers to build test contexts cheaply
    // ------------------------------------------------------------------

    private fun wallet(vararg pockets: Pocket): Wallet = Wallet(
        id = walletId, userId = UserId("user-test"), currency = Currency.EUR,
        pockets = pockets.associateBy { it.id },
    )

    private fun pocket(
        id: PocketId = pocketId,
        name: String = "Food",
        balance: Long = 10_000L,
        benefits: Set<BenefitCategory> = setOf(BenefitCategory.FOOD),
    ) = Pocket(id = id, walletId = walletId, name = name,
        allowedBenefits = benefits, balance = Money(balance, Currency.EUR))

    private fun spendCtx(
        balance: Long = 10_000L,
        amount: Long = 5_000L,
        category: BenefitCategory = BenefitCategory.FOOD,
        merchant: String = "Carrefour",
        backupBalance: Long = 0L,
    ): RuleContext.SpendContext {
        val primary = pocket(balance = balance)
        val backup  = pocket(id = backupId, name = "Backup", balance = backupBalance,
            benefits = setOf(BenefitCategory.GENERAL))
        val w = wallet(primary, backup)
        return RuleContext.SpendContext(
            wallet = w, pocket = primary,
            requestedAmount = Money(amount, Currency.EUR),
            merchant = merchant, benefitCategory = category, reference = "REF-001",
        )
    }

    private fun transferCtx(
        fromBalance: Long = 10_000L,
        amount: Long = 5_000L,
        fromBenefits: Set<BenefitCategory> = setOf(BenefitCategory.FOOD),
        toBenefits: Set<BenefitCategory> = setOf(BenefitCategory.FOOD),
    ): RuleContext.TransferContext {
        val from = pocket(balance = fromBalance, benefits = fromBenefits)
        val to   = pocket(id = backupId, name = "Target", balance = 0L, benefits = toBenefits)
        val w    = wallet(from, to)
        return RuleContext.TransferContext(
            wallet = w, fromPocket = from, toPocket = to,
            requestedAmount = Money(amount, Currency.EUR), reference = "XREF-001",
        )
    }

    // ------------------------------------------------------------------
    // BenefitCategoryRule
    // ------------------------------------------------------------------

    @Test fun `BenefitCategoryRule allows matching category`() {
        val result = BenefitCategoryRule().evaluate(spendCtx(category = BenefitCategory.FOOD))
        assertThat(result).isInstanceOf(RuleDecision.Allow::class.java)
    }

    @Test fun `BenefitCategoryRule denies non-matching category`() {
        val result = BenefitCategoryRule().evaluate(spendCtx(category = BenefitCategory.TRANSPORT))
        assertThat(result).isInstanceOf(RuleDecision.Deny::class.java)
    }

    // ------------------------------------------------------------------
    // MaxTransactionAmountRule
    // ------------------------------------------------------------------

    @Test fun `MaxTransactionAmountRule allows spend within cap`() {
        val result = MaxTransactionAmountRule(Money(10_000L, Currency.EUR)).evaluate(spendCtx(amount = 5_000L))
        assertThat(result).isInstanceOf(RuleDecision.Allow::class.java)
    }

    @Test fun `MaxTransactionAmountRule denies spend over cap`() {
        val result = MaxTransactionAmountRule(Money(3_000L, Currency.EUR)).evaluate(spendCtx(amount = 5_000L))
        assertThat(result).isInstanceOf(RuleDecision.Deny::class.java)
        assertThat((result as RuleDecision.Deny).reason).contains("exceeds cap")
    }

    // ------------------------------------------------------------------
    // MerchantBlocklistRule
    // ------------------------------------------------------------------

    @Test fun `MerchantBlocklistRule allows non-blocked merchant`() {
        val result = MerchantBlocklistRule(setOf("CasinoXYZ")).evaluate(spendCtx(merchant = "Carrefour"))
        assertThat(result).isInstanceOf(RuleDecision.Allow::class.java)
    }

    @Test fun `MerchantBlocklistRule denies blocked merchant case-insensitive`() {
        val result = MerchantBlocklistRule(setOf("CasinoXYZ")).evaluate(spendCtx(merchant = "casinoxyz"))
        assertThat(result).isInstanceOf(RuleDecision.Deny::class.java)
    }

    // ------------------------------------------------------------------
    // OverspendSplitRule
    // ------------------------------------------------------------------

    @Test fun `OverspendSplitRule allows when no shortfall`() {
        val result = OverspendSplitRule(backupId).evaluate(spendCtx(balance = 10_000L, amount = 5_000L))
        assertThat(result).isInstanceOf(RuleDecision.Allow::class.java)
    }

    @Test fun `OverspendSplitRule returns split when shortfall covered`() {
        val ctx = spendCtx(balance = 2_000L, amount = 5_000L, backupBalance = 5_000L)
        val result = OverspendSplitRule(backupId).evaluate(ctx) as RuleDecision.SplitOverspend
        assertThat(result.shortfall.amount).isEqualTo(3_000L)
        assertThat(result.primaryAmount.amount).isEqualTo(2_000L)
        assertThat(result.coverPocketId).isEqualTo(backupId)
    }

    @Test fun `OverspendSplitRule denies when fallback insufficient`() {
        val ctx = spendCtx(balance = 1_000L, amount = 5_000L, backupBalance = 500L)
        assertThat(OverspendSplitRule(backupId).evaluate(ctx)).isInstanceOf(RuleDecision.Deny::class.java)
    }

    // ------------------------------------------------------------------
    // LowBalanceNotifyRule
    // ------------------------------------------------------------------

    @Test fun `LowBalanceNotifyRule emits notification when balance drops below threshold`() {
        val result = LowBalanceNotifyRule(Money(1_000L, Currency.EUR))
            .evaluate(spendCtx(balance = 2_000L, amount = 1_500L))
        assertThat(result).isInstanceOf(RuleDecision.Notify::class.java)
    }

    @Test fun `LowBalanceNotifyRule allows when balance stays above threshold`() {
        val result = LowBalanceNotifyRule(Money(500L, Currency.EUR))
            .evaluate(spendCtx(balance = 10_000L, amount = 1_000L))
        assertThat(result).isInstanceOf(RuleDecision.Allow::class.java)
    }

    // ------------------------------------------------------------------
    // Transfer rules
    // ------------------------------------------------------------------

    @Test fun `TransferSufficientFundsRule allows when funds available`() {
        assertThat(TransferSufficientFundsRule().evaluate(transferCtx(fromBalance = 10_000L, amount = 5_000L)))
            .isInstanceOf(RuleDecision.Allow::class.java)
    }

    @Test fun `TransferSufficientFundsRule denies when insufficient`() {
        assertThat(TransferSufficientFundsRule().evaluate(transferCtx(fromBalance = 1_000L, amount = 5_000L)))
            .isInstanceOf(RuleDecision.Deny::class.java)
    }

    @Test fun `TransferBenefitCompatibilityRule allows shared category`() {
        assertThat(TransferBenefitCompatibilityRule().evaluate(
            transferCtx(fromBenefits = setOf(BenefitCategory.FOOD), toBenefits = setOf(BenefitCategory.FOOD))
        )).isInstanceOf(RuleDecision.Allow::class.java)
    }

    @Test fun `TransferBenefitCompatibilityRule denies incompatible categories`() {
        assertThat(TransferBenefitCompatibilityRule().evaluate(
            transferCtx(fromBenefits = setOf(BenefitCategory.FOOD), toBenefits = setOf(BenefitCategory.TRANSPORT))
        )).isInstanceOf(RuleDecision.Deny::class.java)
    }

    @Test fun `TransferBenefitCompatibilityRule allows when GENERAL is involved`() {
        assertThat(TransferBenefitCompatibilityRule().evaluate(
            transferCtx(fromBenefits = setOf(BenefitCategory.GENERAL), toBenefits = setOf(BenefitCategory.TRANSPORT))
        )).isInstanceOf(RuleDecision.Allow::class.java)
    }

    // ------------------------------------------------------------------
    // Engine AND composition
    // ------------------------------------------------------------------

    @Test fun `engine short-circuits on first Deny`() {
        var thirdEvaluated = false
        val rules = listOf(
            Rule<RuleContext.SpendContext> { RuleDecision.Allow("R1") },
            Rule<RuleContext.SpendContext> { RuleDecision.Deny("R2", "Blocked") },
            Rule<RuleContext.SpendContext> { thirdEvaluated = true; RuleDecision.Allow("R3") },
        )
        val result = engine.evaluateSpend(spendCtx(), rules)
        assertThat(result.isDenied).isTrue()
        assertThat(thirdEvaluated).isFalse()
        assertThat(result.shortCircuitedAt).isEqualTo("R2")
    }

    @Test fun `engine collects notifications when all rules pass`() {
        val rules = listOf(
            Rule<RuleContext.SpendContext> { RuleDecision.Allow("R1") },
            Rule<RuleContext.SpendContext> { RuleDecision.Notify("R2", "Low balance") },
            Rule<RuleContext.SpendContext> { RuleDecision.Notify("R3", "Another alert") },
        )
        val result = engine.evaluateSpend(spendCtx(), rules)
        assertThat(result.isAllowed).isTrue()
        assertThat(result.notifications).hasSize(2)
    }

    @Test fun `engine returns SplitOverspend when rule fires`() {
        val ctx = spendCtx(balance = 1_000L, amount = 4_000L, backupBalance = 5_000L)
        val result = engine.evaluateSpend(ctx, listOf(OverspendSplitRule(backupId)))
        assertThat(result.decision).isInstanceOf(RuleDecision.SplitOverspend::class.java)
        assertThat((result.decision as RuleDecision.SplitOverspend).shortfall.amount).isEqualTo(3_000L)
    }
}

// ---------------------------------------------------------------------------
// Integration — service + policy + rules engine end-to-end
// ---------------------------------------------------------------------------

class RulesIntegrationTest {

    private lateinit var repo: InMemoryWalletRepository
    private lateinit var policyLoader: com.walletdomain.domain.rules.policy.DefaultWalletPolicyLoader
    private lateinit var service: WalletApplicationService

    @BeforeTest fun setup() {
        repo         = InMemoryWalletRepository()
        policyLoader = com.walletdomain.domain.rules.policy.DefaultWalletPolicyLoader()
        service      = WalletApplicationService(repo, walletPolicyLoader = policyLoader)
    }

    private fun walletWithTwoPockets(): Triple<WalletId, PocketId, PocketId> {
        val w  = service.createWallet(CreateWalletCommand(UserId("user-r"), "EUR"))
        val wid = WalletId.of(w.id)
        val p1 = service.createPocket(CreatePocketCommand(wid, "Food", setOf("FOOD")))
        val p2 = service.createPocket(CreatePocketCommand(wid, "General", setOf("GENERAL")))
        return Triple(wid, PocketId.of(p1.id), PocketId.of(p2.id))
    }

    @Test fun `spend denied when merchant is blocked`() {
        val (wid, pid, _) = walletWithTwoPockets()
        service.creditPocket(CreditPocketCommand(wid, pid, 10_000L, "EUR", CreditSource.TOP_UP, "R1"))
        policyLoader.register(WalletPolicy(wid, spend = SpendPolicy(blockedMerchants = listOf("BadMerchant"))))

        assertThatThrownBy {
            service.spend(SpendCommand(wid, pid, 1_000L, "EUR", "BadMerchant", "FOOD", "R2"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("MerchantBlocklistRule")
    }

    @Test fun `spend denied when amount exceeds cap`() {
        val (wid, pid, _) = walletWithTwoPockets()
        service.creditPocket(CreditPocketCommand(wid, pid, 100_000L, "EUR", CreditSource.TOP_UP, "R1"))
        policyLoader.register(WalletPolicy(wid, spend = SpendPolicy(maxTransactionAmount = 5_000L)))

        assertThatThrownBy {
            service.spend(SpendCommand(wid, pid, 10_000L, "EUR", "Carrefour", "FOOD", "R2"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("MaxTransactionAmountRule")
    }

    @Test fun `overspend split covers shortfall from backup pocket`() {
        val (wid, pid, backupId) = walletWithTwoPockets()
        service.creditPocket(CreditPocketCommand(wid, pid, 2_000L, "EUR", CreditSource.TOP_UP, "R1"))
        service.creditPocket(CreditPocketCommand(wid, backupId, 8_000L, "EUR", CreditSource.TOP_UP, "R2"))
        policyLoader.register(WalletPolicy(wid, spend = SpendPolicy(overspendFallbackPocketId = backupId.toString())))

        service.spend(SpendCommand(wid, pid, 5_000L, "EUR", "Carrefour", "FOOD", "R3"))

        assertThat(service.getPocket(wid, pid).balance).isEqualTo(0L)
        assertThat(service.getPocket(wid, backupId).balance).isEqualTo(5_000L) // 8000 - 3000
    }

    @Test fun `transfer denied when categories incompatible`() {
        val w   = service.createWallet(CreateWalletCommand(UserId("user-compat"), "EUR"))
        val wid = WalletId.of(w.id)
        val food  = PocketId.of(service.createPocket(CreatePocketCommand(wid, "Food", setOf("FOOD"))).id)
        val sport = PocketId.of(service.createPocket(CreatePocketCommand(wid, "Sport", setOf("SPORT"))).id)
        service.creditPocket(CreditPocketCommand(wid, food, 10_000L, "EUR", CreditSource.TOP_UP, "R1"))
        policyLoader.register(WalletPolicy(wid, transfer = TransferPolicy(enforceBenefitCompatibility = true)))

        assertThatThrownBy {
            service.transfer(TransferCommand(wid, food, sport, 5_000L, "EUR", "XFER-1"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("TransferBenefitCompatibilityRule")
    }
}
