package com.walletdomain.domain.rules.engine

import com.walletdomain.domain.model.BenefitCategory
import com.walletdomain.domain.model.Money
import com.walletdomain.domain.model.PocketId
import com.walletdomain.domain.rules.model.RuleContext
import com.walletdomain.domain.rules.model.RuleDecision

// ---------------------------------------------------------------------------
// Rule<C> — stateless, pure function. Same input → same output.
// ---------------------------------------------------------------------------

fun interface Rule<in C : RuleContext> {
    fun evaluate(context: C): RuleDecision
}

// ===========================================================================
// SPEND RULES
// ===========================================================================

/** Denies if the pocket doesn't cover the requested benefit category. */
class BenefitCategoryRule : Rule<RuleContext.SpendContext> {
    override fun evaluate(ctx: RuleContext.SpendContext): RuleDecision =
        if (ctx.pocket.canSpendOn(ctx.benefitCategory))
            RuleDecision.Allow("BenefitCategoryRule")
        else
            RuleDecision.Deny(
                ruleName = "BenefitCategoryRule",
                reason = "Pocket '${ctx.pocket.name}' does not cover benefit '${ctx.benefitCategory.code}'",
            )
}

/** Denies if the spend exceeds a configured per-transaction cap. */
class MaxTransactionAmountRule(private val maxAmount: Money) : Rule<RuleContext.SpendContext> {
    override fun evaluate(ctx: RuleContext.SpendContext): RuleDecision =
        if (ctx.requestedAmount.amount > maxAmount.amount)
            RuleDecision.Deny(
                ruleName = "MaxTransactionAmountRule",
                reason = "Amount ${ctx.requestedAmount} exceeds cap $maxAmount",
            )
        else RuleDecision.Allow("MaxTransactionAmountRule")
}

/** Denies if the merchant is on the wallet's blocklist. */
class MerchantBlocklistRule(private val blockedMerchants: Set<String>) : Rule<RuleContext.SpendContext> {
    override fun evaluate(ctx: RuleContext.SpendContext): RuleDecision {
        val blocked = blockedMerchants.any { it.equals(ctx.merchant, ignoreCase = true) }
        return if (blocked)
            RuleDecision.Deny("MerchantBlocklistRule", "Merchant '${ctx.merchant}' is blocked")
        else
            RuleDecision.Allow("MerchantBlocklistRule")
    }
}

/**
 * Covers a shortfall from an ordered list of fallback pockets.
 * If the primary pocket has enough funds → Allow.
 * If a fallback covers the gap → SplitOverspend.
 * If nothing covers it → Deny.
 */
class OverspendSplitRule(private val fallbackPocketId: PocketId) : Rule<RuleContext.SpendContext> {
    override fun evaluate(ctx: RuleContext.SpendContext): RuleDecision {
        if (!ctx.hasShortfall) return RuleDecision.Allow("OverspendSplitRule", "No shortfall")

        val fallback = ctx.otherPockets.firstOrNull { it.id == fallbackPocketId && it.active }
            ?: return RuleDecision.Deny("OverspendSplitRule", "Fallback pocket $fallbackPocketId not found or inactive")

        if (!fallback.balance.isGreaterThanOrEqualTo(ctx.shortfall))
            return RuleDecision.Deny(
                "OverspendSplitRule",
                "Fallback '${fallback.name}' has insufficient funds (available=${fallback.balance}, needed=${ctx.shortfall})",
            )

        return RuleDecision.SplitOverspend(
            ruleName        = "OverspendSplitRule",
            reason          = "Shortfall of ${ctx.shortfall} covered by '${fallback.name}'",
            coverPocketId   = fallback.id,
            coverPocketName = fallback.name,
            shortfall       = ctx.shortfall,
            primaryAmount   = ctx.pocket.balance,
        )
    }
}

/** Emits a non-blocking alert when balance after spend drops below a threshold. */
class LowBalanceNotifyRule(private val threshold: Money) : Rule<RuleContext.SpendContext> {
    override fun evaluate(ctx: RuleContext.SpendContext): RuleDecision {
        val balanceAfter = Money(
            maxOf(0L, ctx.pocket.balance.amount - ctx.requestedAmount.amount),
            ctx.pocket.balance.currency,
        )
        return if (balanceAfter.amount < threshold.amount)
            RuleDecision.Notify(
                ruleName = "LowBalanceNotifyRule",
                reason   = "Balance after spend ($balanceAfter) is below threshold ($threshold)",
                payload  = mapOf(
                    "pocketId"     to ctx.pocket.id.toString(),
                    "pocketName"   to ctx.pocket.name,
                    "balanceAfter" to balanceAfter.amount.toString(),
                    "threshold"    to threshold.amount.toString(),
                    "currency"     to threshold.currency.code,
                ),
            )
        else RuleDecision.Allow("LowBalanceNotifyRule")
    }
}

// ===========================================================================
// TRANSFER RULES
// ===========================================================================

/** Denies if the source pocket lacks sufficient funds. */
class TransferSufficientFundsRule : Rule<RuleContext.TransferContext> {
    override fun evaluate(ctx: RuleContext.TransferContext): RuleDecision =
        if (ctx.hasShortfall)
            RuleDecision.Deny(
                "TransferSufficientFundsRule",
                "Pocket '${ctx.fromPocket.name}' has insufficient funds " +
                    "(balance=${ctx.fromPocket.balance}, requested=${ctx.requestedAmount})",
            )
        else RuleDecision.Allow("TransferSufficientFundsRule")
}

/** Denies if the transfer exceeds a configured cap. */
class MaxTransferAmountRule(private val maxAmount: Money) : Rule<RuleContext.TransferContext> {
    override fun evaluate(ctx: RuleContext.TransferContext): RuleDecision =
        if (ctx.requestedAmount.amount > maxAmount.amount)
            RuleDecision.Deny("MaxTransferAmountRule", "Transfer ${ctx.requestedAmount} exceeds cap $maxAmount")
        else RuleDecision.Allow("MaxTransferAmountRule")
}

/**
 * Denies a transfer if pockets share no compatible benefit category.
 * GENERAL is always compatible with everything.
 */
class TransferBenefitCompatibilityRule : Rule<RuleContext.TransferContext> {
    override fun evaluate(ctx: RuleContext.TransferContext): RuleDecision {
        val from = ctx.fromPocket
        val to   = ctx.toPocket
        if (BenefitCategory.GENERAL in from.allowedBenefits || BenefitCategory.GENERAL in to.allowedBenefits)
            return RuleDecision.Allow("TransferBenefitCompatibilityRule")

        return if (from.allowedBenefits.intersect(to.allowedBenefits).isNotEmpty())
            RuleDecision.Allow("TransferBenefitCompatibilityRule")
        else
            RuleDecision.Deny(
                "TransferBenefitCompatibilityRule",
                "Pockets '${from.name}' and '${to.name}' share no compatible benefit categories",
            )
    }
}

/** Emits a non-blocking alert when a large transfer is detected. */
class LargeTransferNotifyRule(private val threshold: Money) : Rule<RuleContext.TransferContext> {
    override fun evaluate(ctx: RuleContext.TransferContext): RuleDecision =
        if (ctx.requestedAmount.amount >= threshold.amount)
            RuleDecision.Notify(
                ruleName = "LargeTransferNotifyRule",
                reason   = "Large transfer of ${ctx.requestedAmount} detected",
                payload  = mapOf(
                    "fromPocket" to ctx.fromPocket.name,
                    "toPocket"   to ctx.toPocket.name,
                    "amount"     to ctx.requestedAmount.amount.toString(),
                    "currency"   to ctx.requestedAmount.currency.code,
                ),
            )
        else RuleDecision.Allow("LargeTransferNotifyRule")
}
