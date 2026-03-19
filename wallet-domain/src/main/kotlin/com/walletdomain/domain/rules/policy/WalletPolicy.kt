package com.walletdomain.domain.rules.policy

import com.walletdomain.domain.model.Currency
import com.walletdomain.domain.model.Money
import com.walletdomain.domain.model.PocketId
import com.walletdomain.domain.model.WalletId
import com.walletdomain.domain.rules.engine.*
import com.walletdomain.domain.rules.model.RuleContext

data class WalletPolicy(
    val walletId: WalletId,
    val spend: SpendPolicy = SpendPolicy(),
    val transfer: TransferPolicy = TransferPolicy(),
) {
    fun spendRules(): List<Rule<RuleContext.SpendContext>> = buildList {
        add(BenefitCategoryRule())                              // always first
        spend.maxTransactionAmount?.let {
            add(MaxTransactionAmountRule(Money(it, Currency.of(spend.currency))))
        }
        if (spend.blockedMerchants.isNotEmpty()) {
            add(MerchantBlocklistRule(spend.blockedMerchants.toSet()))
        }
        spend.overspendFallbackPocketId?.let { id ->
            add(OverspendSplitRule(PocketId.of(id)))
        }
        spend.lowBalanceNotifyThreshold?.let {
            add(LowBalanceNotifyRule(Money(it, Currency.of(spend.currency))))
        }
    }

    fun transferRules(): List<Rule<RuleContext.TransferContext>> = buildList {
        add(TransferSufficientFundsRule())                      // always first
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
}

data class SpendPolicy(
    val currency: String = "EUR",
    val maxTransactionAmount: Long? = null,
    val blockedMerchants: List<String> = emptyList(),
    val overspendFallbackPocketId: String? = null,
    val lowBalanceNotifyThreshold: Long? = null,
)

data class TransferPolicy(
    val currency: String = "EUR",
    val maxTransferAmount: Long? = null,
    val enforceBenefitCompatibility: Boolean = false,
    val largeTransferNotifyThreshold: Long? = null,
)
