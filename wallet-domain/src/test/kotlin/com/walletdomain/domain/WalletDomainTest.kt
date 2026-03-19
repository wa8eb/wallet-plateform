package com.walletdomain.domain

import com.walletdomain.application.command.*
import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.exception.DomainException
import com.walletdomain.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.*

class WalletDomainTest {

    private lateinit var repo: InMemoryWalletRepository
    private lateinit var service: WalletApplicationService

    @BeforeTest
    fun setup() {
        repo = InMemoryWalletRepository()
        service = WalletApplicationService(repo)
    }

    // ------------------------------------------------------------------
    // Wallet creation
    // ------------------------------------------------------------------

    @Test
    fun `should create a wallet for a user`() {
        val view = service.createWallet(CreateWalletCommand(UserId("user-1"), "EUR"))
        assertThat(view.userId).isEqualTo("user-1")
        assertThat(view.currency).isEqualTo("EUR")
        assertThat(view.totalBalance).isZero()
        assertThat(view.active).isTrue()
    }

    @Test
    fun `should reject duplicate wallet for same user`() {
        service.createWallet(CreateWalletCommand(UserId("user-1"), "EUR"))
        assertThatThrownBy {
            service.createWallet(CreateWalletCommand(UserId("user-1"), "EUR"))
        }.isInstanceOf(DomainException.WalletAlreadyExists::class.java)
    }

    // ------------------------------------------------------------------
    // Pocket management
    // ------------------------------------------------------------------

    @Test
    fun `should create a pocket with allowed benefit categories`() {
        val wallet = service.createWallet(CreateWalletCommand(UserId("user-2"), "EUR"))
        val walletId = WalletId.of(wallet.id)

        val pocket = service.createPocket(
            CreatePocketCommand(walletId, "Food Budget", setOf("FOOD", "CULTURE"))
        )

        assertThat(pocket.name).isEqualTo("Food Budget")
        assertThat(pocket.balance).isZero()
        // allowedCategories is the PocketView DTO field name (API surface)
        assertThat(pocket.allowedCategories.map { it.code }).containsExactlyInAnyOrder("FOOD", "CULTURE")
    }

    @Test
    fun `should reject duplicate pocket name`() {
        val wallet = service.createWallet(CreateWalletCommand(UserId("user-3"), "EUR"))
        val walletId = WalletId.of(wallet.id)
        service.createPocket(CreatePocketCommand(walletId, "Lunch", setOf("FOOD")))

        assertThatThrownBy {
            service.createPocket(CreatePocketCommand(walletId, "Lunch", setOf("FOOD")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ------------------------------------------------------------------
    // Credit & Spend
    // ------------------------------------------------------------------

    @Test
    fun `should credit a pocket and update balance`() {
        val (walletId, pocketId) = createWalletWithPocket("user-4", "FOOD")
        val pocket = service.creditPocket(
            CreditPocketCommand(walletId, pocketId, 5000L, "EUR", CreditSource.TOP_UP, "REF-001")
        )
        assertThat(pocket.balance).isEqualTo(5000L)
    }

    @Test
    fun `should spend from pocket and record ledger entry`() {
        val (walletId, pocketId) = createWalletWithPocket("user-5", "FOOD")
        service.creditPocket(CreditPocketCommand(walletId, pocketId, 10000L, "EUR", CreditSource.TOP_UP, "REF-1"))

        val pocket = service.spend(SpendCommand(walletId, pocketId, 3000L, "EUR", "Carrefour", "FOOD", "REF-2"))

        assertThat(pocket.balance).isEqualTo(7000L)
        val ledger = service.getLedger(walletId, pocketId)
        assertThat(ledger.entries).hasSize(2)
        assertThat(ledger.entries.last().type).isEqualTo("DEBIT")
        assertThat(ledger.entries.last().merchant).isEqualTo("Carrefour")
    }

    @Test
    fun `should reject spend on disallowed benefit category`() {
        val (walletId, pocketId) = createWalletWithPocket("user-6", "FOOD")
        service.creditPocket(CreditPocketCommand(walletId, pocketId, 10000L, "EUR", CreditSource.TOP_UP, "REF-1"))

        // Rules engine intercepts first → InvalidOperation (wraps BenefitCategoryRule denial)
        assertThatThrownBy {
            service.spend(SpendCommand(walletId, pocketId, 1000L, "EUR", "SNCF", "TRANSPORT", "REF-2"))
        }.isInstanceOf(DomainException.InvalidOperation::class.java)
            .hasMessageContaining("BenefitCategoryRule")
    }

    @Test
    fun `should reject spend when insufficient funds`() {
        val (walletId, pocketId) = createWalletWithPocket("user-7", "FOOD")
        service.creditPocket(CreditPocketCommand(walletId, pocketId, 100L, "EUR", CreditSource.TOP_UP, "REF-1"))

        assertThatThrownBy {
            service.spend(SpendCommand(walletId, pocketId, 9999L, "EUR", "Carrefour", "FOOD", "REF-2"))
        }.isInstanceOf(DomainException.InsufficientFunds::class.java)
    }

    // ------------------------------------------------------------------
    // Transfer — exercises debitInternal (bypasses category guard)
    // ------------------------------------------------------------------

    @Test
    fun `should transfer between pockets regardless of source category`() {
        val wallet = service.createWallet(CreateWalletCommand(UserId("user-8"), "EUR"))
        val walletId = WalletId.of(wallet.id)

        // Source is FOOD-only — must still be transferable via debitInternal
        val p1 = service.createPocket(CreatePocketCommand(walletId, "Food", setOf("FOOD")))
        val p2 = service.createPocket(CreatePocketCommand(walletId, "Transport", setOf("TRANSPORT")))
        val pid1 = PocketId.of(p1.id)
        val pid2 = PocketId.of(p2.id)

        service.creditPocket(CreditPocketCommand(walletId, pid1, 10000L, "EUR", CreditSource.TOP_UP, "REF-1"))

        val updated = service.transfer(TransferCommand(walletId, pid1, pid2, 4000L, "EUR", "XFER-1"))

        val src = updated.pockets.first { it.id == p1.id }
        val dst = updated.pockets.first { it.id == p2.id }
        assertThat(src.balance).isEqualTo(6000L)
        assertThat(dst.balance).isEqualTo(4000L)
    }

    @Test
    fun `should reject transfer when source has insufficient funds`() {
        val wallet = service.createWallet(CreateWalletCommand(UserId("user-8b"), "EUR"))
        val walletId = WalletId.of(wallet.id)
        val p1 = service.createPocket(CreatePocketCommand(walletId, "Small", setOf("GENERAL")))
        val p2 = service.createPocket(CreatePocketCommand(walletId, "Target", setOf("GENERAL")))
        service.creditPocket(CreditPocketCommand(walletId, PocketId.of(p1.id), 100L, "EUR", CreditSource.TOP_UP, "R1"))

        assertThatThrownBy {
            service.transfer(TransferCommand(walletId, PocketId.of(p1.id), PocketId.of(p2.id), 9999L, "EUR", "XFER"))
        }.isInstanceOf(DomainException.InsufficientFunds::class.java)
    }

    // ------------------------------------------------------------------
    // Wallet total balance
    // ------------------------------------------------------------------

    @Test
    fun `total balance should aggregate all active pockets`() {
        val wallet = service.createWallet(CreateWalletCommand(UserId("user-9"), "EUR"))
        val walletId = WalletId.of(wallet.id)

        val p1 = service.createPocket(CreatePocketCommand(walletId, "Food", setOf("FOOD")))
        val p2 = service.createPocket(CreatePocketCommand(walletId, "Transport", setOf("TRANSPORT")))

        service.creditPocket(CreditPocketCommand(walletId, PocketId.of(p1.id), 3000L, "EUR", CreditSource.TOP_UP, "R1"))
        service.creditPocket(CreditPocketCommand(walletId, PocketId.of(p2.id), 2000L, "EUR", CreditSource.TOP_UP, "R2"))

        assertThat(service.getWallet(walletId).totalBalance).isEqualTo(5000L)
    }

    // ------------------------------------------------------------------
    // Pocket deactivation
    // ------------------------------------------------------------------

    @Test
    fun `should not allow spending on inactive pocket`() {
        val (walletId, pocketId) = createWalletWithPocket("user-10", "FOOD")
        service.creditPocket(CreditPocketCommand(walletId, pocketId, 5000L, "EUR", CreditSource.TOP_UP, "R1"))
        service.deactivatePocket(DeactivatePocketCommand(walletId, pocketId))

        assertThatThrownBy {
            service.spend(SpendCommand(walletId, pocketId, 100L, "EUR", "Shop", "FOOD", "R2"))
        }.isInstanceOf(DomainException.PocketInactive::class.java)
    }

    // ------------------------------------------------------------------
    // Value objects
    // ------------------------------------------------------------------

    @Test
    fun `Money should prevent negative amounts`() {
        assertThatThrownBy { Money(-1L, Currency.EUR) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Money should prevent currency mismatch`() {
        assertThatThrownBy { Money(100L, Currency.EUR) + Money(100L, Currency.GBP) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Currency code must be 3 uppercase letters`() {
        assertThatThrownBy { Currency("eur") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Currency("EU") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun createWalletWithPocket(userId: String, category: String): Pair<WalletId, PocketId> {
        val wallet = service.createWallet(CreateWalletCommand(UserId(userId), "EUR"))
        val walletId = WalletId.of(wallet.id)
        val pocket = service.createPocket(CreatePocketCommand(walletId, "My Pocket", setOf(category)))
        return walletId to PocketId.of(pocket.id)
    }
}
