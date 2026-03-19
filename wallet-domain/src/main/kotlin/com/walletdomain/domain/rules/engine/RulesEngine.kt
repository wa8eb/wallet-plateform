package com.walletdomain.domain.rules.engine

import com.walletdomain.domain.rules.model.EngineResult
import com.walletdomain.domain.rules.model.RuleContext
import com.walletdomain.domain.rules.model.RuleDecision

// ---------------------------------------------------------------------------
// RulesEngine — AND composition over a list of Rules.
//
// Evaluation order:
//   1. Deny  → stop immediately, return denied EngineResult
//   2. SplitOverspend → record it, keep evaluating
//   3. Notify → record it, keep evaluating
//   4. Allow  → continue
//   All pass → return split (if any) or allow, with all notifications attached
// ---------------------------------------------------------------------------

class RulesEngine {

    fun evaluateSpend(
        ctx: RuleContext.SpendContext,
        rules: List<Rule<RuleContext.SpendContext>>,
    ): EngineResult = evaluate(ctx, rules)

    fun evaluateTransfer(
        ctx: RuleContext.TransferContext,
        rules: List<Rule<RuleContext.TransferContext>>,
    ): EngineResult = evaluate(ctx, rules)

    private fun <C : RuleContext> evaluate(ctx: C, rules: List<Rule<C>>): EngineResult {
        val ruleNames     = mutableListOf<String>()
        val notifications = mutableListOf<RuleDecision.Notify>()
        var split: RuleDecision.SplitOverspend? = null

        for (rule in rules) {
            val decision = rule.evaluate(ctx)
            ruleNames += decision.ruleName

            when (decision) {
                is RuleDecision.Deny         -> return EngineResult.denied(decision, ruleNames)
                is RuleDecision.SplitOverspend -> split = decision
                is RuleDecision.Notify       -> notifications += decision
                is RuleDecision.Allow        -> Unit
            }
        }

        // All rules passed — build final result
        val finalDecision: RuleDecision = split ?: RuleDecision.Allow("Engine", "All ${rules.size} rules passed")
        return EngineResult(
            decision       = finalDecision,
            evaluatedRules = ruleNames,
            notifications  = notifications,
        )
    }
}

fun EngineResult.summary(): String = when (val d = decision) {
    is RuleDecision.Allow          -> "APPROVED — ${evaluatedRules.size} rules passed"
    is RuleDecision.SplitOverspend -> "APPROVED WITH SPLIT — primary: ${d.primaryAmount}, cover '${d.coverPocketName}': ${d.shortfall}"
    is RuleDecision.Deny           -> "REJECTED by '${d.ruleName}': ${d.reason}"
    is RuleDecision.Notify         -> "APPROVED WITH NOTIFICATION"
}
