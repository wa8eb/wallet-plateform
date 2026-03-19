package com.walletdomain.domain.rules.model

import com.walletdomain.domain.model.*

// ---------------------------------------------------------------------------
// RuleContext — immutable snapshot passed into the rules engine.
// Uses Pocket directly (it's a Kotlin data class — immutable, safe).
// PocketSnapshot class removed.
// ---------------------------------------------------------------------------

sealed class RuleContext {

    data class SpendContext(
        val wallet: Wallet,
        val pocket: Pocket,                  // the pocket being spent from
        val requestedAmount: Money,
        val merchant: String,
        val benefitCategory: BenefitCategory,
        val reference: String,
    ) : RuleContext() {
        val shortfall: Money
            get() = if (requestedAmount.amount > pocket.balance.amount)
                Money(requestedAmount.amount - pocket.balance.amount, requestedAmount.currency)
            else Money.zero(requestedAmount.currency)

        val hasShortfall: Boolean get() = shortfall.amount > 0L

        // Convenience — other active pockets available for overspend cover
        val otherPockets: List<Pocket>
            get() = wallet.activePockets.filter { it.id != pocket.id }
    }

    data class TransferContext(
        val wallet: Wallet,
        val fromPocket: Pocket,
        val toPocket: Pocket,
        val requestedAmount: Money,
        val reference: String,
    ) : RuleContext() {
        val hasShortfall: Boolean
            get() = requestedAmount.amount > fromPocket.balance.amount
    }
}
