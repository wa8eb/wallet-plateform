package com.walletdomain.domain.model

import java.time.Instant

sealed class LedgerEntry {
    abstract val id: LedgerEntryId
    abstract val pocketId: PocketId
    abstract val amount: Money
    abstract val occurredAt: Instant
    abstract val reference: String

    data class Credit(
        override val id: LedgerEntryId = LedgerEntryId.generate(),
        override val pocketId: PocketId,
        override val amount: Money,
        override val reference: String,
        override val occurredAt: Instant = Instant.now(),
        val source: CreditSource,
    ) : LedgerEntry()

    data class Debit(
        override val id: LedgerEntryId = LedgerEntryId.generate(),
        override val pocketId: PocketId,
        override val amount: Money,
        override val reference: String,
        override val occurredAt: Instant = Instant.now(),
        val merchant: String,
        val benefitCategory: BenefitCategory,   // renamed from goodsCategory
    ) : LedgerEntry()
}

enum class CreditSource { TOP_UP, TRANSFER, REFUND }
