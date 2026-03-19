package com.walletdomain.domain.model

import java.util.UUID

// ---------------------------------------------------------------------------
// Identifiers — inline value classes, zero runtime overhead
// ---------------------------------------------------------------------------

@JvmInline
value class UserId(val value: String) {
    init { require(value.isNotBlank()) { "UserId must not be blank" } }
    override fun toString() = value
}

@JvmInline
value class WalletId(val value: UUID = UUID.randomUUID()) {
    override fun toString() = value.toString()
    companion object {
        fun of(raw: String) = WalletId(UUID.fromString(raw))
        fun generate() = WalletId()
    }
}

@JvmInline
value class PocketId(val value: UUID = UUID.randomUUID()) {
    override fun toString() = value.toString()
    companion object {
        fun of(raw: String) = PocketId(UUID.fromString(raw))
        fun generate() = PocketId()
    }
}

@JvmInline
value class LedgerEntryId(val value: UUID = UUID.randomUUID()) {
    override fun toString() = value.toString()
    companion object { fun generate() = LedgerEntryId() }
}

// ---------------------------------------------------------------------------
// Money — immutable, currency-aware, arithmetic operators
// ---------------------------------------------------------------------------

data class Money(val amount: Long, val currency: Currency) {

    init { require(amount >= 0) { "Money amount cannot be negative: $amount" } }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        require(amount >= other.amount) { "Insufficient funds: $amount < ${other.amount}" }
        return Money(amount - other.amount, currency)
    }

    fun isGreaterThanOrEqualTo(other: Money): Boolean {
        requireSameCurrency(other)
        return amount >= other.amount
    }

    private fun requireSameCurrency(other: Money) =
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }

    override fun toString() = "$amount ${currency.code}"

    companion object {
        fun of(amount: Long, currencyCode: String) = Money(amount, Currency.of(currencyCode))
        fun zero(currency: Currency) = Money(0L, currency)
    }
}

// ---------------------------------------------------------------------------
// Currency
// ---------------------------------------------------------------------------

data class Currency(val code: String) {
    init {
        require(code.length == 3 && code.all { it.isUpperCase() }) {
            "Currency code must be 3 uppercase letters: $code"
        }
    }
    override fun toString() = code
    companion object {
        val EUR = Currency("EUR")
        val GBP = Currency("GBP")
        val USD = Currency("USD")
        fun of(code: String) = Currency(code.uppercase())
    }
}
