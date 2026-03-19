package com.walletdomain.domain.rules.policy

import com.walletdomain.domain.model.Currency
import com.walletdomain.domain.model.Money
import com.walletdomain.domain.model.PocketId
import com.walletdomain.domain.rules.engine.*
import com.walletdomain.domain.rules.model.RuleContext

// ---------------------------------------------------------------------------
// GlobalPolicy — instance-wide rules that apply to EVERY wallet and pocket.
//
// Loaded once from policy-global.json at startup.
// Combined with per-wallet rules in the engine: global runs first, then wallet.
// Both must pass (AND across levels).
//
// JSON format (policy-global.json):
// {
//   "spend": {
//     "currency": "EUR",
//     "maxTransactionAmount": 100000,
//     "blockedMerchants": ["CasinoXYZ", "BettingCo"],
//     "lowBalanceNotifyThreshold": 500
//   },
//   "transfer": {
//     "currency": "EUR",
//     "maxTransferAmount": 500000,
//     "enforceBenefitCompatibility": false,
//     "largeTransferNotifyThreshold": 50000
//   }
// }
//
// Note: overspendFallbackPocketId is intentionally absent at global level —
// fallback pockets are wallet-specific, not instance-wide.
// ---------------------------------------------------------------------------

data class GlobalPolicy(
    val spend: GlobalSpendPolicy = GlobalSpendPolicy(),
    val transfer: GlobalTransferPolicy = GlobalTransferPolicy(),
) {
    fun spendRules(): List<Rule<RuleContext.SpendContext>> = buildList {
        spend.maxTransactionAmount?.let {
            add(MaxTransactionAmountRule(Money(it, Currency.of(spend.currency))))
        }
        if (spend.blockedMerchants.isNotEmpty()) {
            add(MerchantBlocklistRule(spend.blockedMerchants.toSet()))
        }
        spend.lowBalanceNotifyThreshold?.let {
            add(LowBalanceNotifyRule(Money(it, Currency.of(spend.currency))))
        }
    }

    fun transferRules(): List<Rule<RuleContext.TransferContext>> = buildList {
        transfer.maxTransferAmount?.let {
            add(MaxTransferAmountRule(Money(it, Currency.of(transfer.currency))))
        }
        if (transfer.enforceBenefitCompatibility) {
            add(TransferBenefitCompatibilityRule())
        }
        transfer.largeTransferNotifyThreshold?.let {
            add(LargeTransferNotifyRule(Money(it, Currency.of(transfer.currency))))
        }
    }

    companion object {
        /** Permissive defaults — used when no global policy file is present. */
        val PERMISSIVE = GlobalPolicy()
    }
}

data class GlobalSpendPolicy(
    val currency: String = "EUR",
    /** Hard ceiling per transaction for the entire instance. null = no cap. */
    val maxTransactionAmount: Long? = null,
    /** Merchants always blocked across all wallets. */
    val blockedMerchants: List<String> = emptyList(),
    /** Notify when any pocket balance drops below this after a spend. null = no alert. */
    val lowBalanceNotifyThreshold: Long? = null,
)

data class GlobalTransferPolicy(
    val currency: String = "EUR",
    /** Hard ceiling per transfer for the entire instance. null = no cap. */
    val maxTransferAmount: Long? = null,
    /** When true, all transfers across incompatible categories are denied globally. */
    val enforceBenefitCompatibility: Boolean = false,
    /** Notify when any transfer exceeds this threshold. null = no alert. */
    val largeTransferNotifyThreshold: Long? = null,
)

// ---------------------------------------------------------------------------
// GlobalPolicyLoader — port interface
// ---------------------------------------------------------------------------

interface GlobalPolicyLoader {
    /** Returns the current global policy. Called on every operation — must be fast. */
    fun load(): GlobalPolicy
}

/** Always returns the given policy. Used in tests and when no file is configured. */
class StaticGlobalPolicyLoader(private val policy: GlobalPolicy = GlobalPolicy.PERMISSIVE) : GlobalPolicyLoader {
    override fun load() = policy
}
