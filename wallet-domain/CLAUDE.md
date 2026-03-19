# CLAUDE.md — wallet-domain: Adding a New Rule

This document explains the exact steps to add a new business rule to the
rules engine. The domain is zero-dependency Kotlin — no Spring, no Vert.x.
All rules are pure functions. The engine composes them with AND semantics.

---

## Architecture recap

```
RuleContext (SpendContext | TransferContext)
    │
    ▼
Rule<C>.evaluate(context: C): RuleOutcome
    │
    ▼
RulesEngine.evaluateSpend / evaluateTransfer
    │
    ▼
EngineDecision (Approved | ApprovedWithSplit | Rejected)
```

Rules are declared in `wallet-domain/src/main/kotlin/com/walletdomain/domain/rules/engine/Rules.kt`.
The engine is in `RulesEngine.kt`. Policy config lives in `WalletPolicy.kt`.

---

## Step-by-step: adding a new spend rule

### 1. Decide what the rule guards

Ask yourself:
- Does it fire on **spend** (`SpendContext`) or **transfer** (`TransferContext`)?
- What is the **deny condition**?
- Does it need data that isn't already in the context?

If the context is missing data, add a field to `RuleContext.SpendContext` or
`RuleContext.TransferContext` in `RuleContext.kt` and update the builder
calls in `WalletApplicationService`.

---

### 2. Implement the rule class

Add your rule to `Rules.kt` (keep all rules in one file for discoverability):

```kotlin
/**
 * Denies spend if the merchant country is outside the wallet's allowed regions.
 * Useful for geo-restricted benefit programs.
 */
class MerchantCountryRule(
    private val allowedCountryCodes: Set<String>,
) : Rule<RuleContext.SpendContext> {

    override fun evaluate(context: RuleContext.SpendContext): RuleOutcome {
        // allowedCountryCodes empty = no restriction
        if (allowedCountryCodes.isEmpty()) return RuleOutcome.Allow("MerchantCountryRule")

        return if (context.merchantCountryCode in allowedCountryCodes) {
            RuleOutcome.Allow("MerchantCountryRule")
        } else {
            RuleOutcome.Deny(
                ruleName = "MerchantCountryRule",
                reason = "Merchant country '${context.merchantCountryCode}' is not in " +
                    "the allowed set: $allowedCountryCodes",
            )
        }
    }
}
```

**Rules must be:**
- **Stateless** — no mutable fields
- **Pure** — same input → same output, no I/O, no side effects
- **Focused** — one concern per rule

**Allowed return types:**
| Type | Meaning |
|---|---|
| `RuleOutcome.Allow` | Rule passes, continue chain |
| `RuleOutcome.Deny` | Operation blocked, engine stops |
| `RuleOutcome.Notify` | Rule passes but emit an alert event |
| `RuleOutcome.SplitOverspend` | Cover shortfall from another pocket |

---

### 3. Expose it in WalletPolicy

Open `WalletPolicy.kt`. Add a config field to `SpendPolicy`:

```kotlin
data class SpendPolicy(
    // ... existing fields ...
    /** ISO-3166-1 alpha-2 country codes. Empty = no restriction. */
    val allowedMerchantCountries: List<String> = emptyList(),
)
```

Then wire it in `spendRules()`:

```kotlin
fun spendRules(): List<Rule<RuleContext.SpendContext>> = buildList {
    // ... existing rules ...
    if (spend.allowedMerchantCountries.isNotEmpty()) {
        add(MerchantCountryRule(spend.allowedMerchantCountries.toSet()))
    }
}
```

---

### 4. Add the field to SpendContext (if needed)

If your rule needs data not already in the context, add it to
`RuleContext.SpendContext` in `RuleContext.kt`:

```kotlin
data class SpendContext(
    // ... existing fields ...
    val merchantCountryCode: String = "FR",   // default for backward compat
) : RuleContext()
```

Then update `WalletApplicationService.spend()` to populate it:

```kotlin
val context = RuleContext.SpendContext(
    // ... existing mappings ...
    merchantCountryCode = command.merchantCountryCode,
)
```

---

### 5. Write the unit test

Add a test class or extend `RulesEngineTest.kt`:

```kotlin
@Test
fun `MerchantCountryRule allows merchant in allowed country`() {
    val rule = MerchantCountryRule(setOf("FR", "DE"))
    val ctx = spendCtx() // helper in RulesEngineTest
    // Override the country if you added it to the context
    assertThat(rule.evaluate(ctx)).isInstanceOf(RuleOutcome.Allow::class.java)
}

@Test
fun `MerchantCountryRule denies merchant in blocked country`() {
    val rule = MerchantCountryRule(setOf("FR", "DE"))
    // ctx with merchantCountryCode = "US"
    assertThat(rule.evaluate(ctx)).isInstanceOf(RuleOutcome.Deny::class.java)
}

@Test
fun `MerchantCountryRule allows anything when list is empty`() {
    val rule = MerchantCountryRule(emptySet())
    assertThat(rule.evaluate(spendCtx())).isInstanceOf(RuleOutcome.Allow::class.java)
}
```

**Coverage requirements:**
- Happy path (Allow)
- Denial path (Deny)
- Edge cases: empty config, boundary values
- Engine AND composition: rule fires after others pass

---

### 6. Update the policy YAML example

Document the new field in the policy file comment block in `WalletPolicyLoader.kt`:

```
 * "spend": {
 *   "allowedMerchantCountries": ["FR", "DE", "GB"]
 * }
```

---

## Transfer rules — same pattern

Transfer rules implement `Rule<RuleContext.TransferContext>` and are wired
via `WalletPolicy.transferRules()`. All the same steps apply — just use
`TransferContext` instead of `SpendContext`.

---

## Rule naming conventions

| Convention | Example |
|---|---|
| Name ends with `Rule` | `MerchantCountryRule` |
| Constructor params = config | `MerchantCountryRule(allowedCountryCodes)` |
| `ruleName` string = class name | `"MerchantCountryRule"` |
| Denial reason = human sentence | `"Merchant country 'US' is not in the allowed set"` |

---

## What NOT to do

- Do **not** inject repositories or call I/O inside a rule
- Do **not** throw exceptions — return `RuleOutcome.Deny` instead
- Do **not** put rule logic in `WalletApplicationService` — it belongs here
- Do **not** share mutable state between rules
- Do **not** add Spring/Vert.x imports to this module
