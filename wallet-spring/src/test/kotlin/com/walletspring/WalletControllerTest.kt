package com.walletspring

import com.fasterxml.jackson.databind.ObjectMapper
import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.rules.policy.DefaultWalletPolicyLoader
import com.walletdomain.domain.rules.policy.GlobalPolicy
import com.walletdomain.domain.rules.policy.StaticGlobalPolicyLoader
import com.walletspring.adapter.inbound.rest.DomainExceptionHandler
import com.walletspring.adapter.inbound.rest.PolicyAdminController
import com.walletspring.adapter.inbound.rest.WalletController
import com.walletspring.adapter.outbound.JsonGlobalPolicyLoader
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(WalletController::class, PolicyAdminController::class)
@Import(
    WalletControllerTest.TestConfig::class,
    DomainExceptionHandler::class,
    SecurityConfig::class,
)
class WalletControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper

    // ------------------------------------------------------------------
    // Test wiring — real in-memory domain, permissive global policy, fake JWT
    // ------------------------------------------------------------------

    @Configuration
    class TestConfig {
        @Bean fun repo() = InMemoryWalletRepository()
        @Bean fun walletPolicyLoader() = DefaultWalletPolicyLoader()
        @Bean fun globalPolicyLoader(om: ObjectMapper) =
            JsonGlobalPolicyLoader(om).also {
                // Use permissive defaults — tests assert domain behaviour, not policy caps
            }
        @Bean fun service(
            repo: InMemoryWalletRepository,
            walletPolicyLoader: DefaultWalletPolicyLoader,
            globalPolicyLoader: JsonGlobalPolicyLoader,
        ) = WalletApplicationService(
            walletRepository   = repo,
            walletPolicyLoader = walletPolicyLoader,
            globalPolicyLoader = globalPolicyLoader,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private val userId = "user-test-1"
    private val token  = jwt().jwt { it.subject(userId).claim("roles", listOf("ROLE_USER")) }

    private fun createWallet(): String {
        val result = mvc.post("/wallets") {
            with(token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"currencyCode":"EUR"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return mapper.readTree(result)["id"].asText()
    }

    private fun createPocket(walletId: String, name: String, vararg benefits: String): String {
        val benefitsJson = benefits.joinToString(",", "[\"", "\"]")
        val result = mvc.post("/wallets/$walletId/pockets") {
            with(token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","allowedBenefits":$benefitsJson}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return mapper.readTree(result)["id"].asText()
    }

    // ------------------------------------------------------------------
    // Wallet tests
    // ------------------------------------------------------------------

    @Test
    fun `POST wallets returns 201 with wallet view`() {
        mvc.post("/wallets") {
            with(token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"currencyCode":"EUR"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.userId") { value(userId) }
            jsonPath("$.currency") { value("EUR") }
            jsonPath("$.active") { value(true) }
            jsonPath("$.totalBalance") { value(0) }
        }
    }

    @Test
    fun `POST wallets returns 409 on duplicate user`() {
        createWallet()
        mvc.post("/wallets") {
            with(token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"currencyCode":"EUR"}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `GET wallet returns 404 for unknown id`() {
        mvc.get("/wallets/00000000-0000-0000-0000-000000000000") {
            with(token)
        }.andExpect { status { isNotFound() } }
    }

    // ------------------------------------------------------------------
    // Pocket tests
    // ------------------------------------------------------------------

    @Test
    fun `POST pockets creates pocket and returns 201`() {
        val walletId = createWallet()
        mvc.post("/wallets/$walletId/pockets") {
            with(token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Food","allowedBenefits":["FOOD"]}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Food") }
            jsonPath("$.balance") { value(0) }
        }
    }

    // ------------------------------------------------------------------
    // Financial tests
    // ------------------------------------------------------------------

    @Test
    fun `full lifecycle — create, credit, spend, ledger`() {
        val walletId = createWallet()
        val pocketId = createPocket(walletId, "Food", "FOOD")

        mvc.post("/wallets/$walletId/pockets/$pocketId/credit") {
            with(token); contentType = MediaType.APPLICATION_JSON
            content = """{"amount":10000,"currencyCode":"EUR","source":"TOP_UP","reference":"INIT"}"""
        }.andExpect { status { isOk() }; jsonPath("$.balance") { value(10000) } }

        mvc.post("/wallets/$walletId/pockets/$pocketId/spend") {
            with(token); contentType = MediaType.APPLICATION_JSON
            content = """{"amount":3000,"currencyCode":"EUR","merchant":"Carrefour","benefitCategory":"FOOD","reference":"S1"}"""
        }.andExpect { status { isOk() }; jsonPath("$.balance") { value(7000) } }

        mvc.get("/wallets/$walletId/pockets/$pocketId/ledger") {
            with(token)
        }.andExpect {
            status { isOk() }
            jsonPath("$.entries.length()") { value(2) }
        }
    }

    @Test
    fun `spend on wrong category returns 422 POLICY_VIOLATION`() {
        val walletId = createWallet()
        val pocketId = createPocket(walletId, "Food", "FOOD")

        mvc.post("/wallets/$walletId/pockets/$pocketId/credit") {
            with(token); contentType = MediaType.APPLICATION_JSON
            content = """{"amount":5000,"currencyCode":"EUR","source":"TOP_UP","reference":"I"}"""
        }

        mvc.post("/wallets/$walletId/pockets/$pocketId/spend") {
            with(token); contentType = MediaType.APPLICATION_JSON
            content = """{"amount":1000,"currencyCode":"EUR","merchant":"SNCF","benefitCategory":"TRANSPORT","reference":"S2"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("POLICY_VIOLATION") }
        }
    }

    @Test
    fun `transfer between pockets succeeds`() {
        val freshToken = jwt().jwt { it.subject("user-transfer").claim("roles", listOf("ROLE_USER")) }
        val walletId   = mapper.readTree(
            mvc.post("/wallets") {
                with(freshToken); contentType = MediaType.APPLICATION_JSON
                content = """{"currencyCode":"EUR"}"""
            }.andReturn().response.contentAsString
        )["id"].asText()

        val p1 = createPocketAs(freshToken, walletId, "Source", "GENERAL")
        val p2 = createPocketAs(freshToken, walletId, "Target", "FOOD")

        mvc.post("/wallets/$walletId/pockets/$p1/credit") {
            with(freshToken); contentType = MediaType.APPLICATION_JSON
            content = """{"amount":8000,"currencyCode":"EUR","source":"TOP_UP","reference":"R"}"""
        }

        mvc.post("/wallets/$walletId/transfer") {
            with(freshToken); contentType = MediaType.APPLICATION_JSON
            content = """{"fromPocketId":"$p1","toPocketId":"$p2","amount":3000,"currencyCode":"EUR","reference":"XFER"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `unauthenticated request returns 401`() {
        mvc.get("/wallets/anything").andExpect { status { isUnauthorized() } }
    }

    // ------------------------------------------------------------------
    // Policy admin tests
    // ------------------------------------------------------------------

    @Test
    fun `GET admin policy global returns current policy`() {
        mvc.get("/admin/policy/global") {
            with(token)
        }.andExpect {
            status { isOk() }
            jsonPath("$.spend") { exists() }
            jsonPath("$.transfer") { exists() }
        }
    }

    @Test
    fun `POST admin policy reload returns reloaded status`() {
        mvc.post("/admin/policy/global/reload") {
            with(token)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("reloaded") }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun createPocketAs(
        token: org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor,
        walletId: String,
        name: String,
        vararg benefits: String,
    ): String {
        val benefitsJson = benefits.joinToString(",", "[\"", "\"]")
        return mapper.readTree(
            mvc.post("/wallets/$walletId/pockets") {
                with(token); contentType = MediaType.APPLICATION_JSON
                content = """{"name":"$name","allowedBenefits":$benefitsJson}"""
            }.andReturn().response.contentAsString
        )["id"].asText()
    }
}
