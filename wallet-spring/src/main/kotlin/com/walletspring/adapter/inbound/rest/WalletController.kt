package com.walletspring.adapter.inbound.rest

import com.walletdomain.application.command.*
import com.walletdomain.application.query.*
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.exception.DomainException
import com.walletdomain.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/wallets")
class WalletController(private val service: WalletApplicationService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createWallet(
        @AuthenticationPrincipal userId: String,
        @RequestBody body: CreateWalletRequest,
    ): WalletView = io { service.createWallet(CreateWalletCommand(UserId(userId), body.currencyCode)) }

    @GetMapping("/{walletId}")
    suspend fun getWallet(@PathVariable walletId: String): WalletView =
        io { service.getWallet(WalletId.of(walletId)) }

    @DeleteMapping("/{walletId}")
    suspend fun closeWallet(@PathVariable walletId: String): WalletView =
        io { service.closeWallet(CloseWalletCommand(WalletId.of(walletId))) }

    @PostMapping("/{walletId}/pockets")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createPocket(
        @PathVariable walletId: String,
        @RequestBody body: CreatePocketRequest,
    ): PocketView = io {
        service.createPocket(CreatePocketCommand(WalletId.of(walletId), body.name, body.allowedBenefits))
    }

    @GetMapping("/{walletId}/pockets/{pocketId}")
    suspend fun getPocket(@PathVariable walletId: String, @PathVariable pocketId: String): PocketView =
        io { service.getPocket(WalletId.of(walletId), PocketId.of(pocketId)) }

    @DeleteMapping("/{walletId}/pockets/{pocketId}")
    suspend fun deactivatePocket(@PathVariable walletId: String, @PathVariable pocketId: String): PocketView =
        io { service.deactivatePocket(DeactivatePocketCommand(WalletId.of(walletId), PocketId.of(pocketId))) }

    @PostMapping("/{walletId}/pockets/{pocketId}/credit")
    suspend fun credit(
        @PathVariable walletId: String,
        @PathVariable pocketId: String,
        @RequestBody body: CreditRequest,
    ): PocketView = io {
        service.creditPocket(CreditPocketCommand(
            WalletId.of(walletId), PocketId.of(pocketId),
            body.amount, body.currencyCode, CreditSource.valueOf(body.source), body.reference,
        ))
    }

    @PostMapping("/{walletId}/pockets/{pocketId}/spend")
    suspend fun spend(
        @PathVariable walletId: String,
        @PathVariable pocketId: String,
        @RequestBody body: SpendRequest,
    ): PocketView = io {
        service.spend(SpendCommand(
            WalletId.of(walletId), PocketId.of(pocketId),
            body.amount, body.currencyCode, body.merchant, body.benefitCategory, body.reference,
        ))
    }

    @PostMapping("/{walletId}/transfer")
    suspend fun transfer(
        @PathVariable walletId: String,
        @RequestBody body: TransferRequest,
    ): WalletView = io {
        service.transfer(TransferCommand(
            WalletId.of(walletId),
            PocketId.of(body.fromPocketId), PocketId.of(body.toPocketId),
            body.amount, body.currencyCode, body.reference,
        ))
    }

    @GetMapping("/{walletId}/pockets/{pocketId}/ledger")
    suspend fun ledger(@PathVariable walletId: String, @PathVariable pocketId: String): LedgerView =
        io { service.getLedger(WalletId.of(walletId), PocketId.of(pocketId)) }

    @PostMapping("/{walletId}/policy/reload")
    suspend fun reloadPolicy(@PathVariable walletId: String): Map<String, String> =
        mapOf("reloaded" to walletId)
}

@RestControllerAdvice
class DomainExceptionHandler {
    @ExceptionHandler(DomainException::class)
    fun handle(ex: DomainException): ResponseEntity<ErrorResponse> {
        val (status, code) = when (ex) {
            is DomainException.WalletNotFound      -> 404 to "WALLET_NOT_FOUND"
            is DomainException.PocketNotFound      -> 404 to "POCKET_NOT_FOUND"
            is DomainException.WalletAlreadyExists -> 409 to "WALLET_ALREADY_EXISTS"
            is DomainException.WalletInactive      -> 422 to "WALLET_INACTIVE"
            is DomainException.PocketInactive      -> 422 to "POCKET_INACTIVE"
            is DomainException.InsufficientFunds   -> 422 to "INSUFFICIENT_FUNDS"
            is DomainException.CategoryNotAllowed  -> 422 to "CATEGORY_NOT_ALLOWED"
            is DomainException.InvalidOperation    -> 422 to "POLICY_VIOLATION"
        }
        return ResponseEntity.status(status).body(ErrorResponse(code, ex.message ?: "Error"))
    }
}

data class CreateWalletRequest(val currencyCode: String)
data class CreatePocketRequest(val name: String, val allowedBenefits: Set<String>)
data class CreditRequest(val amount: Long, val currencyCode: String, val source: String, val reference: String)
data class SpendRequest(val amount: Long, val currencyCode: String, val merchant: String, val benefitCategory: String, val reference: String)
data class TransferRequest(val fromPocketId: String, val toPocketId: String, val amount: Long, val currencyCode: String, val reference: String)
data class ErrorResponse(val code: String, val message: String)

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }


// ---------------------------------------------------------------------------
// PolicyAdminController — inspect and hot-reload the global policy
// ---------------------------------------------------------------------------

@RestController
@RequestMapping("/admin/policy")
class PolicyAdminController(private val globalPolicyLoader: com.walletspring.adapter.outbound.JsonGlobalPolicyLoader) {

    /** Returns the currently active global policy (useful for auditing). */
    @GetMapping("/global")
    fun getGlobalPolicy() = globalPolicyLoader.load()

    /**
     * Re-reads policy-global.json from disk and replaces the cached policy atomically.
     * No restart required. Returns the newly loaded policy.
     */
    @PostMapping("/global/reload")
    fun reloadGlobalPolicy() = mapOf(
        "status"  to "reloaded",
        "policy"  to globalPolicyLoader.reload(),
    )
}
