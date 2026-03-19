package com.walletdomain.domain

import com.walletdomain.application.command.*
import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.exception.DomainException
import com.walletdomain.domain.model.*
import com.walletdomain.domain.rules.policy.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.*

// ---------------------------------------------------------------------------
// GlobalPolicyTest — verifies that global rules apply to every wallet/pocket
// and that global + wallet rules both run (AND across levels).
// ---------------------------------------------------------------------------

class GlobalPolicyTest {

    private fun service(global: GlobalPolicy, walletPolicy: WalletPolicy? = null): WalletApplicationService {
        val repo         = InMemoryWalletRepository()
        val walletLoader = DefaultWalletPolicyLoader().also {
            if (walletPolicy != null) it.register(walletPolicy)
        }
        return WalletApplicationService(
            walletRepository   = repo,
            walletPolicyLoader = walletLoader,
            globalPolicyLoader = StaticGlobalPolicyLoader(global),
        )
    }

    // Convenience: create a wallet + FOOD pocket + credit it
    private fun WalletApplicationService.setup(
        userId: String = "user-1",
        balance: Long = 100_000L,
    ): Triple<WalletId, PocketId, WalletApplicationService> {
        val w = createWallet(CreateWalletCommand(UserId(userId), "EUR"))
        val wid = WalletId.of(w.id)
        val p = createPocket(CreatePocketCommand(wid, "Food", setOf("FOOD")))
        val pid = PocketId.of(p.id)
        creditPocket(CreditPocketCommand(wid, pid, balance, "EUR", CreditSource.TOP_UP, "INIT"))
        return Triple(wid, pid, this)
    }

    // ------------------------------------------------------------------
    // Global spend cap applies to every wallet
    // ------------------------------------------------------------------

    @Test
    fun `global spend cap blocks every wallet`() {
        val svc = service(GlobalPolicy(spend = GlobalSpendPolicy(maxTransactionAmount = 1_000L)))
        val (wid, pid) = svc.setup()

        assertThatThrownBy {
            svc.spend(SpendCommand(wid, pid, 2_000L, "EUR", "Carrefour", "FOOD", "R1"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("MaxTransactionAmountRule")
    }

    @Test
    fun `global spend cap allows spend within limit`() {
        val svc = service(GlobalPolicy(spend = GlobalSpendPolicy(maxTransactionAmount = 5_000L)))
        val (wid, pid) = svc.setup()

        val pocket = svc.spend(SpendCommand(wid, pid, 3_000L, "EUR", "Carrefour", "FOOD", "R1"))
        assertThat(pocket.balance).isEqualTo(97_000L)
    }

    // ------------------------------------------------------------------
    // Global merchant blocklist applies to every wallet
    // ------------------------------------------------------------------

    @Test
    fun `globally blocked merchant is denied across all wallets`() {
        val svc = service(GlobalPolicy(spend = GlobalSpendPolicy(blockedMerchants = listOf("BadCasino"))))
        val (wid, pid) = svc.setup("user-a")
        val (wid2, pid2) = svc.setup("user-b")

        // Both wallets blocked
        assertThatThrownBy {
            svc.spend(SpendCommand(wid, pid, 100L, "EUR", "BadCasino", "FOOD", "R1"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)

        assertThatThrownBy {
            svc.spend(SpendCommand(wid2, pid2, 100L, "EUR", "badcasino", "FOOD", "R2")) // case insensitive
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
    }

    // ------------------------------------------------------------------
    // AND across levels: global passes, wallet rule fails → denied
    // ------------------------------------------------------------------

    @Test
    fun `AND composition — global passes but wallet rule fails`() {
        val wid = WalletId.generate()
        // Global: allows up to 50000; wallet: only allows 1000
        val walletPolicy = WalletPolicy(
            walletId = wid,
            spend    = SpendPolicy(maxTransactionAmount = 1_000L),
        )
        val svc = service(
            GlobalPolicy(spend = GlobalSpendPolicy(maxTransactionAmount = 50_000L)),
            walletPolicy,
        )
        // Register wallet with the known ID via a real wallet creation
        val repo = InMemoryWalletRepository()
        val walletLoader = DefaultWalletPolicyLoader().also { it.register(walletPolicy) }
        val realSvc = WalletApplicationService(
            walletRepository   = repo,
            walletPolicyLoader = walletLoader,
            globalPolicyLoader = StaticGlobalPolicyLoader(
                GlobalPolicy(spend = GlobalSpendPolicy(maxTransactionAmount = 50_000L))
            ),
        )
        val w   = realSvc.createWallet(CreateWalletCommand(UserId("user-and"), "EUR"))
        val actualWid = WalletId.of(w.id)
        val p   = realSvc.createPocket(CreatePocketCommand(actualWid, "Food", setOf("FOOD")))
        val pid = PocketId.of(p.id)
        realSvc.creditPocket(CreditPocketCommand(actualWid, pid, 100_000L, "EUR", CreditSource.TOP_UP, "I"))

        // Register wallet-specific policy using the actual wallet ID
        walletLoader.register(WalletPolicy(actualWid, spend = SpendPolicy(maxTransactionAmount = 1_000L)))

        // 2000 < 50000 (global passes) but 2000 > 1000 (wallet fails)
        assertThatThrownBy {
            realSvc.spend(SpendCommand(actualWid, pid, 2_000L, "EUR", "Shop", "FOOD", "R1"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("MaxTransactionAmountRule")
    }

    // ------------------------------------------------------------------
    // AND across levels: wallet rule fails, global rule would pass → denied
    // ------------------------------------------------------------------

    @Test
    fun `AND composition — wallet rule fails regardless of global`() {
        val repo = InMemoryWalletRepository()
        val walletLoader = DefaultWalletPolicyLoader()
        val svc = WalletApplicationService(
            walletRepository   = repo,
            walletPolicyLoader = walletLoader,
            // Permissive global
            globalPolicyLoader = StaticGlobalPolicyLoader(GlobalPolicy.PERMISSIVE),
        )
        val w   = svc.createWallet(CreateWalletCommand(UserId("user-wfail"), "EUR"))
        val wid = WalletId.of(w.id)
        val p   = svc.createPocket(CreatePocketCommand(wid, "Food", setOf("FOOD")))
        val pid = PocketId.of(p.id)
        svc.creditPocket(CreditPocketCommand(wid, pid, 100_000L, "EUR", CreditSource.TOP_UP, "I"))

        walletLoader.register(WalletPolicy(wid, spend = SpendPolicy(blockedMerchants = listOf("WalletBlockedMerchant"))))

        assertThatThrownBy {
            svc.spend(SpendCommand(wid, pid, 100L, "EUR", "WalletBlockedMerchant", "FOOD", "R1"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("MerchantBlocklistRule")
    }

    // ------------------------------------------------------------------
    // Global transfer cap applies to every transfer
    // ------------------------------------------------------------------

    @Test
    fun `global transfer cap blocks large transfers`() {
        val svc = service(GlobalPolicy(transfer = GlobalTransferPolicy(maxTransferAmount = 5_000L)))
        val w   = svc.createWallet(CreateWalletCommand(UserId("user-tx"), "EUR"))
        val wid = WalletId.of(w.id)
        val p1  = PocketId.of(svc.createPocket(CreatePocketCommand(wid, "From", setOf("GENERAL"))).id)
        val p2  = PocketId.of(svc.createPocket(CreatePocketCommand(wid, "To",   setOf("GENERAL"))).id)
        svc.creditPocket(CreditPocketCommand(wid, p1, 50_000L, "EUR", CreditSource.TOP_UP, "I"))

        assertThatThrownBy {
            svc.transfer(TransferCommand(wid, p1, p2, 10_000L, "EUR", "XFER"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("MaxTransferAmountRule")
    }

    // ------------------------------------------------------------------
    // Permissive global — no restrictions, wallet policy still runs
    // ------------------------------------------------------------------

    @Test
    fun `permissive global does not block anything on its own`() {
        val svc = service(GlobalPolicy.PERMISSIVE)
        val (wid, pid) = svc.setup()

        val pocket = svc.spend(SpendCommand(wid, pid, 50_000L, "EUR", "Carrefour", "FOOD", "R1"))
        assertThat(pocket.balance).isEqualTo(50_000L)
    }
}
