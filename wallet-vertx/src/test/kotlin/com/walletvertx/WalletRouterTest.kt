package com.walletvertx

import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.port.NoOpEventPublisher
import com.walletdomain.domain.rules.policy.StaticGlobalPolicyLoader
import com.walletvertx.adapter.inbound.JwtHandler
import com.walletvertx.adapter.inbound.WalletRouter
import com.walletvertx.adapter.outbound.JsonGlobalPolicyLoader
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

// ---------------------------------------------------------------------------
// WalletRouterTest — real Vert.x HTTP server on a random port.
// JWT tokens generated directly — no IdP needed.
// GlobalPolicy uses permissive defaults (StaticGlobalPolicyLoader).
// ---------------------------------------------------------------------------

@ExtendWith(VertxExtension::class)
class WalletRouterTest {

    private val secret = "wallet-super-secret-key-32chars!!"
    private val jwt    = JwtHandler(secret)
    private val token  = jwt.generateToken("user-vertx-test")

    private lateinit var client: WebClient
    private var port = 0

    @BeforeEach
    fun start(vertx: Vertx, ctx: VertxTestContext) {
        val repo               = InMemoryWalletRepository()
        val globalPolicyLoader = JsonGlobalPolicyLoader(mapper)   // loads from classpath policy-global.json
        val service            = WalletApplicationService(
            walletRepository   = repo,
            eventPublisher     = NoOpEventPublisher,
            globalPolicyLoader = globalPolicyLoader,
        )
        val router = WalletRouter(service, globalPolicyLoader, jwt).build(vertx)

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(0)
            .onSuccess { server ->
                port   = server.actualPort()
                client = WebClient.create(vertx)
                ctx.completeNow()
            }
            .onFailure(ctx::failNow)
    }

    @AfterEach
    fun stop(vertx: Vertx, ctx: VertxTestContext) {
        vertx.close().onComplete { ctx.completeNow() }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `GET health returns 200`(ctx: VertxTestContext) {
        client.get(port, "localhost", "/health").send()
            .onSuccess { resp ->
                ctx.verify {
                    assertThat(resp.statusCode()).isEqualTo(200)
                    assertThat(resp.bodyAsJsonObject().getString("status")).isEqualTo("UP")
                    ctx.completeNow()
                }
            }.onFailure(ctx::failNow)
    }

    @Test
    fun `POST wallets creates wallet and returns 201`(ctx: VertxTestContext) {
        post("/wallets", """{"currencyCode":"EUR"}""")
            .onSuccess { resp ->
                ctx.verify {
                    assertThat(resp.statusCode()).isEqualTo(201)
                    assertThat(resp.bodyAsJsonObject().getString("currency")).isEqualTo("EUR")
                    assertThat(resp.bodyAsJsonObject().getBoolean("active")).isTrue()
                    ctx.completeNow()
                }
            }.onFailure(ctx::failNow)
    }

    @Test
    fun `unauthenticated request returns 401`(ctx: VertxTestContext) {
        client.get(port, "localhost", "/wallets/anything").send()
            .onSuccess { resp ->
                ctx.verify {
                    assertThat(resp.statusCode()).isEqualTo(401)
                    ctx.completeNow()
                }
            }.onFailure(ctx::failNow)
    }

    @Test
    fun `full lifecycle — create wallet, pocket, credit, spend`(ctx: VertxTestContext) {
        createWallet()
            .compose { wid -> createPocket(wid, "Food", "FOOD").map { wid to it } }
            .compose { (wid, pid) -> topUp(wid, pid, 10_000L).map { wid to pid } }
            .compose { (wid, pid) -> spend(wid, pid, 3_000L, "FOOD") }
            .onSuccess { resp ->
                ctx.verify {
                    assertThat(resp.statusCode()).isEqualTo(200)
                    assertThat(resp.bodyAsJsonObject().getLong("balance")).isEqualTo(7_000L)
                    ctx.completeNow()
                }
            }.onFailure(ctx::failNow)
    }

    @Test
    fun `spend on wrong category returns 422`(ctx: VertxTestContext) {
        createWallet()
            .compose { wid -> createPocket(wid, "Food", "FOOD").map { wid to it } }
            .compose { (wid, pid) -> topUp(wid, pid, 5_000L).map { wid to pid } }
            .compose { (wid, pid) -> spend(wid, pid, 100L, "TRANSPORT") }
            .onSuccess { resp ->
                ctx.verify {
                    assertThat(resp.statusCode()).isEqualTo(422)
                    ctx.completeNow()
                }
            }.onFailure(ctx::failNow)
    }

    @Test
    fun `GET admin policy global returns policy`(ctx: VertxTestContext) {
        client.get(port, "localhost", "/admin/policy/global")
            .putHeader("Authorization", "Bearer $token")
            .send()
            .onSuccess { resp ->
                ctx.verify {
                    assertThat(resp.statusCode()).isEqualTo(200)
                    assertThat(resp.bodyAsJsonObject().containsKey("spend")).isTrue()
                    ctx.completeNow()
                }
            }.onFailure(ctx::failNow)
    }

    // ------------------------------------------------------------------
    // Helpers — Future-based for clean composition
    // ------------------------------------------------------------------

    private fun buf(body: String) = io.vertx.core.buffer.Buffer.buffer(body)

    private fun createWallet() =
        client.post(port, "localhost", "/wallets")
            .putHeader("Authorization", "Bearer $token")
            .putHeader("Content-Type", "application/json")
            .sendBuffer(buf("""{"currencyCode":"EUR"}"""))
            .map { it.bodyAsJsonObject().getString("id") }

    private fun createPocket(walletId: String, name: String, benefit: String) =
        client.post(port, "localhost", "/wallets/$walletId/pockets")
            .putHeader("Authorization", "Bearer $token")
            .putHeader("Content-Type", "application/json")
            .sendBuffer(buf("""{"name":"$name","allowedBenefits":["$benefit"]}"""))
            .map { it.bodyAsJsonObject().getString("id") }

    private fun topUp(walletId: String, pocketId: String, amount: Long) =
        client.post(port, "localhost", "/wallets/$walletId/pockets/$pocketId/credit")
            .putHeader("Authorization", "Bearer $token")
            .putHeader("Content-Type", "application/json")
            .sendBuffer(buf("""{"amount":$amount,"currencyCode":"EUR","source":"TOP_UP","reference":"INIT"}"""))

    private fun spend(walletId: String, pocketId: String, amount: Long, category: String) =
        client.post(port, "localhost", "/wallets/$walletId/pockets/$pocketId/spend")
            .putHeader("Authorization", "Bearer $token")
            .putHeader("Content-Type", "application/json")
            .sendBuffer(buf("""{"amount":$amount,"currencyCode":"EUR","merchant":"Shop","benefitCategory":"$category","reference":"S1"}"""))

    private fun post(path: String, body: String) =
        client.post(port, "localhost", path)
            .putHeader("Authorization", "Bearer $token")
            .putHeader("Content-Type", "application/json")
            .sendBuffer(buf(body))
}
