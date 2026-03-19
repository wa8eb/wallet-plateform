package com.walletvertx.adapter.inbound

import com.walletdomain.application.command.*
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.exception.DomainException
import com.walletdomain.domain.model.*
import com.walletvertx.adapter.outbound.JsonGlobalPolicyLoader
import com.walletvertx.mapper
import io.vertx.core.Vertx
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WalletRouter(
    private val service: WalletApplicationService,
    private val globalPolicyLoader: JsonGlobalPolicyLoader,
    private val jwt: JwtHandler,
) {
    private val io = CoroutineScope(Dispatchers.IO)

    fun build(vertx: Vertx): Router = Router.router(vertx).apply {
        route().handler(BodyHandler.create())
        route().handler { ctx ->
            ctx.response().putHeader("Content-Type", "application/json")
            ctx.next()
        }

        // Public
        get("/health").handler { ctx -> ctx.json(200, mapOf("status" to "UP")) }
        post("/auth/token").handler { ctx ->
            val userId = ctx.bodyAsJson?.getString("userId") ?: "demo"
            ctx.json(200, mapOf("token" to jwt.generateToken(userId)))
        }

        // Policy admin — authenticated
        route("/admin*").handler(jwt::handle)
        get("/admin/policy/global").handler { ctx ->
            ctx.json(200, globalPolicyLoader.load())
        }
        post("/admin/policy/global/reload").handler { ctx ->
            ctx.json(200, mapOf("status" to "reloaded", "policy" to globalPolicyLoader.reload()))
        }

        // Wallet endpoints — authenticated
        route("/wallets*").handler(jwt::handle)

        post("/wallets").coHandler { ctx ->
            val userId = ctx.get<String>("userId")
            val body   = ctx.bodyAsJson
            ctx.json(201, service.createWallet(CreateWalletCommand(UserId(userId), body.getString("currencyCode", "EUR"))))
        }

        get("/wallets/:walletId").coHandler { ctx ->
            ctx.json(200, service.getWallet(WalletId.of(ctx.pathParam("walletId"))))
        }

        delete("/wallets/:walletId").coHandler { ctx ->
            ctx.json(200, service.closeWallet(CloseWalletCommand(WalletId.of(ctx.pathParam("walletId")))))
        }

        post("/wallets/:walletId/pockets").coHandler { ctx ->
            val body = ctx.bodyAsJson
            @Suppress("UNCHECKED_CAST")
            val benefits = (body.getJsonArray("allowedBenefits")?.list as? List<String> ?: listOf("GENERAL")).toSet()
            ctx.json(201, service.createPocket(
                CreatePocketCommand(WalletId.of(ctx.pathParam("walletId")), body.getString("name"), benefits)
            ))
        }

        get("/wallets/:walletId/pockets/:pocketId").coHandler { ctx ->
            ctx.json(200, service.getPocket(
                WalletId.of(ctx.pathParam("walletId")), PocketId.of(ctx.pathParam("pocketId"))
            ))
        }

        delete("/wallets/:walletId/pockets/:pocketId").coHandler { ctx ->
            ctx.json(200, service.deactivatePocket(
                DeactivatePocketCommand(WalletId.of(ctx.pathParam("walletId")), PocketId.of(ctx.pathParam("pocketId")))
            ))
        }

        post("/wallets/:walletId/pockets/:pocketId/credit").coHandler { ctx ->
            val b = ctx.bodyAsJson
            ctx.json(200, service.creditPocket(CreditPocketCommand(
                WalletId.of(ctx.pathParam("walletId")), PocketId.of(ctx.pathParam("pocketId")),
                b.getLong("amount"), b.getString("currencyCode", "EUR"),
                CreditSource.valueOf(b.getString("source", "TOP_UP")), b.getString("reference"),
            )))
        }

        post("/wallets/:walletId/pockets/:pocketId/spend").coHandler { ctx ->
            val b = ctx.bodyAsJson
            ctx.json(200, service.spend(SpendCommand(
                WalletId.of(ctx.pathParam("walletId")), PocketId.of(ctx.pathParam("pocketId")),
                b.getLong("amount"), b.getString("currencyCode", "EUR"),
                b.getString("merchant"), b.getString("benefitCategory"), b.getString("reference"),
            )))
        }

        post("/wallets/:walletId/transfer").coHandler { ctx ->
            val b = ctx.bodyAsJson
            ctx.json(200, service.transfer(TransferCommand(
                WalletId.of(ctx.pathParam("walletId")),
                PocketId.of(b.getString("fromPocketId")), PocketId.of(b.getString("toPocketId")),
                b.getLong("amount"), b.getString("currencyCode", "EUR"), b.getString("reference"),
            )))
        }

        get("/wallets/:walletId/pockets/:pocketId/ledger").coHandler { ctx ->
            ctx.json(200, service.getLedger(
                WalletId.of(ctx.pathParam("walletId")), PocketId.of(ctx.pathParam("pocketId"))
            ))
        }
    }

    private fun Route.coHandler(block: suspend (RoutingContext) -> Unit) {
        handler { ctx ->
            io.launch {
                runCatching { block(ctx) }
                    .onFailure { ex -> ctx.handleError(ex) }
            }
        }
    }

    private fun RoutingContext.handleError(ex: Throwable) {
        val (status, code) = when (ex) {
            is DomainException.WalletNotFound      -> 404 to "WALLET_NOT_FOUND"
            is DomainException.PocketNotFound      -> 404 to "POCKET_NOT_FOUND"
            is DomainException.WalletAlreadyExists -> 409 to "WALLET_ALREADY_EXISTS"
            is DomainException.WalletInactive      -> 422 to "WALLET_INACTIVE"
            is DomainException.PocketInactive      -> 422 to "POCKET_INACTIVE"
            is DomainException.InsufficientFunds   -> 422 to "INSUFFICIENT_FUNDS"
            is DomainException.CategoryNotAllowed  -> 422 to "CATEGORY_NOT_ALLOWED"
            is DomainException.InvalidOperation    -> 422 to "POLICY_VIOLATION"
            else                                   -> 500 to "INTERNAL_ERROR"
        }
        response().setStatusCode(status)
            .end(mapper.writeValueAsString(mapOf("code" to code, "message" to (ex.message ?: "Error"))))
    }

    private fun RoutingContext.json(status: Int, body: Any) =
        response().setStatusCode(status).end(mapper.writeValueAsString(body))
}
