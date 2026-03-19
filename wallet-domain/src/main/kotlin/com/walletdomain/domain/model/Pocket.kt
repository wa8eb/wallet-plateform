package com.walletdomain.domain.model

import com.walletdomain.domain.event.DomainEvent
import com.walletdomain.domain.exception.DomainException
import java.time.Instant

data class Pocket(
    val id: PocketId = PocketId.generate(),
    val walletId: WalletId,
    val name: String,
    val allowedBenefits: Set<BenefitCategory>,
    val balance: Money,
    val ledger: List<LedgerEntry> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val active: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "Pocket name must not be blank" }
        require(allowedBenefits.isNotEmpty()) { "Pocket must allow at least one benefit category" }
    }

    /** External spend — enforces benefit category restriction. Pre-authorised by rules engine. */
    fun spend(amount: Money, merchant: String, benefitCategory: BenefitCategory, reference: String): Pair<Pocket, List<DomainEvent>> {
        requireActive()
        requireBenefitAllowed(benefitCategory)
        requireSufficientFunds(amount)
        val entry = LedgerEntry.Debit(pocketId = id, amount = amount, reference = reference,
            merchant = merchant, benefitCategory = benefitCategory)
        return copy(balance = balance - amount, ledger = ledger + entry) to
            listOf(DomainEvent.PocketDebited(walletId, id, amount, merchant, benefitCategory, reference))
    }

    /**
     * Internal debit — used for inter-pocket transfers only.
     * Bypasses benefit category guard: transfers are not subject to merchant/category restrictions.
     * Still enforces active status and sufficient funds.
     */
    fun debitInternal(amount: Money, reference: String): Pair<Pocket, List<DomainEvent>> {
        requireActive()
        requireSufficientFunds(amount)
        val entry = LedgerEntry.Debit(pocketId = id, amount = amount, reference = reference,
            merchant = "INTERNAL_TRANSFER", benefitCategory = BenefitCategory.GENERAL)
        return copy(balance = balance - amount, ledger = ledger + entry) to
            listOf(DomainEvent.PocketDebited(walletId, id, amount, "INTERNAL_TRANSFER", BenefitCategory.GENERAL, reference))
    }

    fun credit(amount: Money, source: CreditSource, reference: String): Pair<Pocket, List<DomainEvent>> {
        requireActive()
        val entry = LedgerEntry.Credit(pocketId = id, amount = amount, reference = reference, source = source)
        return copy(balance = balance + amount, ledger = ledger + entry) to
            listOf(DomainEvent.PocketCredited(walletId, id, amount, source, reference))
    }

    fun deactivate(): Pair<Pocket, List<DomainEvent>> {
        if (!active) return this to emptyList()
        return copy(active = false) to listOf(DomainEvent.PocketDeactivated(walletId, id))
    }

    fun canSpendOn(category: BenefitCategory) = allowedBenefits.contains(category)
    fun hasSufficientFunds(amount: Money) = balance.isGreaterThanOrEqualTo(amount)

    private fun requireActive() { if (!active) throw DomainException.PocketInactive(id) }
    private fun requireBenefitAllowed(c: BenefitCategory) { if (!canSpendOn(c)) throw DomainException.CategoryNotAllowed(id, c) }
    private fun requireSufficientFunds(a: Money) { if (!hasSufficientFunds(a)) throw DomainException.InsufficientFunds(id, balance, a) }

    companion object {
        fun create(walletId: WalletId, name: String, allowedBenefits: Set<BenefitCategory>, currency: Currency): Pair<Pocket, List<DomainEvent>> {
            val pocket = Pocket(walletId = walletId, name = name, allowedBenefits = allowedBenefits, balance = Money.zero(currency))
            return pocket to listOf(DomainEvent.PocketCreated(walletId, pocket.id, name, allowedBenefits))
        }
    }
}
