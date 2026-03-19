package com.walletdomain.application.query

import com.walletdomain.domain.model.*
import java.time.Instant

// ---------------------------------------------------------------------------
// Query Results — read-side projections (no domain aggregate leakage)
// ---------------------------------------------------------------------------

data class WalletView(
    val id: String,
    val userId: String,
    val currency: String,
    val totalBalance: Long,
    val active: Boolean,
    val pockets: List<PocketView>,
    val createdAt: Instant,
)

data class PocketView(
    val id: String,
    val walletId: String,
    val name: String,
    val balance: Long,
    val currency: String,
    val allowedCategories: List<CategoryView>,
    val active: Boolean,
    val createdAt: Instant,
)

data class CategoryView(
    val code: String,
    val label: String,
)

data class LedgerView(
    val pocketId: String,
    val entries: List<LedgerEntryView>,
)

data class LedgerEntryView(
    val id: String,
    val type: String,           // CREDIT | DEBIT
    val amount: Long,
    val currency: String,
    val reference: String,
    val occurredAt: Instant,
    // Credit-specific
    val source: String? = null,
    // Debit-specific
    val merchant: String? = null,
    val category: String? = null,
)

// ---------------------------------------------------------------------------
// Mappers — Wallet/Pocket → View
// ---------------------------------------------------------------------------

fun Wallet.toView() = WalletView(
    id = id.toString(),
    userId = userId.value,
    currency = currency.code,
    totalBalance = totalBalance.amount,
    active = active,
    pockets = pockets.values.map { it.toView() },
    createdAt = createdAt,
)

fun Pocket.toView() = PocketView(
    id = id.toString(),
    walletId = walletId.toString(),
    name = name,
    balance = balance.amount,
    currency = balance.currency.code,
    allowedCategories = allowedBenefits.map { CategoryView(it.code, it.label) },
    active = active,
    createdAt = createdAt,
)

fun List<LedgerEntry>.toView(pocketId: PocketId) = LedgerView(
    pocketId = pocketId.toString(),
    entries = map { entry ->
        when (entry) {
            is LedgerEntry.Credit -> LedgerEntryView(
                id = entry.id.toString(),
                type = "CREDIT",
                amount = entry.amount.amount,
                currency = entry.amount.currency.code,
                reference = entry.reference,
                occurredAt = entry.occurredAt,
                source = entry.source.name,
            )
            is LedgerEntry.Debit -> LedgerEntryView(
                id = entry.id.toString(),
                type = "DEBIT",
                amount = entry.amount.amount,
                currency = entry.amount.currency.code,
                reference = entry.reference,
                occurredAt = entry.occurredAt,
                merchant = entry.merchant,
                category = entry.benefitCategory.code,
            )
        }
    }
)
