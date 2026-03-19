package com.walletdomain.domain.rules.model

import com.walletdomain.domain.model.Money
import com.walletdomain.domain.model.PocketId

// ---------------------------------------------------------------------------
// RuleDecision — single sealed class replacing the former RuleOutcome (4
// variants) + EngineDecision (3 variants). Now just 4 variants total.
// ---------------------------------------------------------------------------

sealed class RuleDecision {

    abstract val ruleName: String
    abstract val reason: String

    data class Allow(
        override val ruleName: String,
        override val reason: String = "Rule passed",
    ) : RuleDecision()

    data class Deny(
        override val ruleName: String,
        override val reason: String,
    ) : RuleDecision()

    data class Notify(
        override val ruleName: String,
        override val reason: String,
        val payload: Map<String, String> = emptyMap(),
    ) : RuleDecision()

    data class SplitOverspend(
        override val ruleName: String,
        override val reason: String,
        val coverPocketId: PocketId,
        val coverPocketName: String,
        val shortfall: Money,
        val primaryAmount: Money,
    ) : RuleDecision()
}

// ---------------------------------------------------------------------------
// EngineResult — the engine's final answer, with full audit trail
// ---------------------------------------------------------------------------

data class EngineResult(
    val decision: RuleDecision,
    val evaluatedRules: List<String>,
    val notifications: List<RuleDecision.Notify> = emptyList(),
    val shortCircuitedAt: String? = null,
) {
    val isAllowed: Boolean get() = decision !is RuleDecision.Deny
    val isDenied:  Boolean get() = decision is RuleDecision.Deny

    companion object {
        fun denied(deny: RuleDecision.Deny, rules: List<String>) =
            EngineResult(deny, rules, shortCircuitedAt = deny.ruleName)
    }
}
