package com.walletvertx.adapter.outbound

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.walletdomain.domain.rules.policy.GlobalPolicy
import com.walletdomain.domain.rules.policy.GlobalPolicyLoader
import com.walletdomain.domain.rules.policy.GlobalSpendPolicy
import com.walletdomain.domain.rules.policy.GlobalTransferPolicy
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------------
// JsonGlobalPolicyLoader — reads policy-global.json from the classpath or
// a filesystem path, compiles it into a GlobalPolicy and caches it.
//
// Thread-safe: AtomicReference ensures a consistent view under concurrent reads.
// Hot-reload: call reload() to re-read the file without restarting the service.
// ---------------------------------------------------------------------------

class JsonGlobalPolicyLoader(
    private val mapper: ObjectMapper,
    private val filePath: String = "policy-global.json",
) : GlobalPolicyLoader {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cached = AtomicReference(loadFromDisk())

    override fun load(): GlobalPolicy = cached.get()

    /** Re-reads the file and atomically replaces the cached policy. */
    fun reload(): GlobalPolicy {
        val fresh = loadFromDisk()
        cached.set(fresh)
        log.info("Global policy reloaded from '{}'", filePath)
        return fresh
    }

    private fun loadFromDisk(): GlobalPolicy {
        // 1. Try filesystem path (useful for mounted Docker volumes)
        val fsFile = File(filePath)
        if (fsFile.exists()) {
            log.info("Loading global policy from filesystem: {}", fsFile.absolutePath)
            return parse(fsFile.readText())
        }

        // 2. Try classpath
        val stream = javaClass.classLoader.getResourceAsStream(filePath)
        if (stream != null) {
            log.info("Loading global policy from classpath: {}", filePath)
            return parse(stream.bufferedReader().readText())
        }

        // 3. No file found — use permissive defaults and warn
        log.warn(
            "No global policy file found at '{}' (filesystem or classpath). " +
            "Using permissive defaults — all transactions allowed.", filePath
        )
        return GlobalPolicy.PERMISSIVE
    }

    private fun parse(json: String): GlobalPolicy {
        val dto = mapper.readValue(json, GlobalPolicyDto::class.java)
        return GlobalPolicy(
            spend = GlobalSpendPolicy(
                currency                  = dto.spend.currency,
                maxTransactionAmount      = dto.spend.maxTransactionAmount,
                blockedMerchants          = dto.spend.blockedMerchants,
                lowBalanceNotifyThreshold = dto.spend.lowBalanceNotifyThreshold,
            ),
            transfer = GlobalTransferPolicy(
                currency                     = dto.transfer.currency,
                maxTransferAmount            = dto.transfer.maxTransferAmount,
                enforceBenefitCompatibility  = dto.transfer.enforceBenefitCompatibility,
                largeTransferNotifyThreshold = dto.transfer.largeTransferNotifyThreshold,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// DTOs — @JsonIgnoreProperties so comments (_comment, _reload) don't break parsing
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GlobalPolicyDto(
    val spend: GlobalSpendDto = GlobalSpendDto(),
    val transfer: GlobalTransferDto = GlobalTransferDto(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GlobalSpendDto(
    val currency: String = "EUR",
    val maxTransactionAmount: Long? = null,
    val blockedMerchants: List<String> = emptyList(),
    val lowBalanceNotifyThreshold: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GlobalTransferDto(
    val currency: String = "EUR",
    val maxTransferAmount: Long? = null,
    val enforceBenefitCompatibility: Boolean = false,
    val largeTransferNotifyThreshold: Long? = null,
)
