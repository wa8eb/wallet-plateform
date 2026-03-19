package com.walletdomain.domain.rules.policy

import com.walletdomain.domain.model.WalletId

// ---------------------------------------------------------------------------
// WalletPolicyLoader — port (interface) for loading a WalletPolicy.
// Implementations can read from classpath, filesystem, database, or config server.
// ---------------------------------------------------------------------------

interface WalletPolicyLoader {
    /**
     * Load the policy for the given wallet.
     * Returns [WalletPolicy] with permissive defaults if no policy file exists.
     */
    fun load(walletId: WalletId): WalletPolicy
}

// ---------------------------------------------------------------------------
// DefaultWalletPolicyLoader — reads from a Map (in-memory, populated from JSON/YAML).
// In production, swap for a file-based or DB-backed implementation.
// ---------------------------------------------------------------------------

class DefaultWalletPolicyLoader(
    private val policies: MutableMap<WalletId, WalletPolicy> = mutableMapOf(),
) : WalletPolicyLoader {

    override fun load(walletId: WalletId): WalletPolicy =
        policies[walletId] ?: WalletPolicy(walletId = walletId) // permissive defaults

    fun register(policy: WalletPolicy) {
        policies[policy.walletId] = policy
    }
}

// ---------------------------------------------------------------------------
// JsonWalletPolicyLoader — loads from wallet-policy-{walletId}.json files.
// Requires a JSON parser available in the runtime layer (not the domain).
// The domain exposes the data model; parsing lives in the adapter layer.
// ---------------------------------------------------------------------------

/**
 * Policy file format (JSON):
 *
 * {
 *   "walletId": "550e8400-e29b-41d4-a716-446655440000",
 *   "spend": {
 *     "currency": "EUR",
 *     "maxTransactionAmount": 50000,
 *     "blockedMerchants": ["CasinoXYZ"],
 *     "overspendFallbackPocketId": "660e8400-...",
 *     "lowBalanceNotifyThreshold": 1000
 *   },
 *   "transfer": {
 *     "currency": "EUR",
 *     "maxTransferAmount": 100000,
 *     "enforceBenefitCompatibility": true,
 *     "largeTransferNotifyThreshold": 20000
 *   }
 * }
 */
object PolicyFileConvention {
    fun fileNameFor(walletId: WalletId) = "wallet-policy-${walletId}.json"
    const val CLASSPATH_PREFIX = "policies/"
}
