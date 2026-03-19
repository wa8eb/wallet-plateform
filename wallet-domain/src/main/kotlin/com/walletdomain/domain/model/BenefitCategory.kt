package com.walletdomain.domain.model

// ---------------------------------------------------------------------------
// BenefitCategory — what a pocket's balance can be spent on
// Replaces the former GoodsCategory concept.
// ---------------------------------------------------------------------------

data class BenefitCategory(val code: String, val label: String) {
    init { require(code.isNotBlank()) { "BenefitCategory code must not be blank" } }

    override fun equals(other: Any?): Boolean =
        other is BenefitCategory && code.uppercase() == other.code.uppercase()

    override fun hashCode(): Int = code.uppercase().hashCode()

    override fun toString() = code

    companion object {
        val FOOD        = BenefitCategory("FOOD",      "Food & Groceries")
        val TRANSPORT   = BenefitCategory("TRANSPORT", "Transport & Mobility")
        val CULTURE     = BenefitCategory("CULTURE",   "Culture & Entertainment")
        val SPORT       = BenefitCategory("SPORT",     "Sport & Wellness")
        val GENERAL     = BenefitCategory("GENERAL",   "General Purpose")
        val CHILDCARE   = BenefitCategory("CHILDCARE", "Childcare & Education")
        val HEALTH      = BenefitCategory("HEALTH",    "Health & Medical")

        private val builtIn = listOf(FOOD, TRANSPORT, CULTURE, SPORT, GENERAL, CHILDCARE, HEALTH)
            .associateBy { it.code.uppercase() }

        fun of(code: String): BenefitCategory =
            builtIn[code.uppercase()] ?: BenefitCategory(code.uppercase(), code)
    }
}
