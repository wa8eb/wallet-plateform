package com.walletvertx

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.port.NoOpEventPublisher
import com.walletdomain.domain.rules.policy.DefaultWalletPolicyLoader
import com.walletvertx.adapter.inbound.JwtHandler
import com.walletvertx.adapter.inbound.WalletRouter
import com.walletvertx.adapter.outbound.JsonGlobalPolicyLoader
import io.vertx.core.Vertx
import io.vertx.core.json.jackson.DatabindCodec
import org.slf4j.LoggerFactory

val log = LoggerFactory.getLogger("WalletVertx")

val mapper = jacksonObjectMapper().apply {
    registerModule(JavaTimeModule())
    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}

fun main() {
    // Configure Vert.x JSON codec to use our mapper
    DatabindCodec.mapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    val jwtSecret  = System.getenv("JWT_SECRET")          ?: "wallet-super-secret-key-32chars!!"
    val port       = System.getenv("PORT")?.toInt()        ?: 8081
    val policyFile = System.getenv("GLOBAL_POLICY_FILE")  ?: "policy-global.json"

    val repository       = InMemoryWalletRepository()
    val walletPolicyLoader = DefaultWalletPolicyLoader()
    val globalPolicyLoader = JsonGlobalPolicyLoader(mapper, policyFile)

    val service = WalletApplicationService(
        walletRepository   = repository,
        eventPublisher     = NoOpEventPublisher,
        walletPolicyLoader = walletPolicyLoader,
        globalPolicyLoader = globalPolicyLoader,
    )

    val vertx  = Vertx.vertx()
    val router = WalletRouter(service, globalPolicyLoader, JwtHandler(jwtSecret)).build(vertx)

    vertx.createHttpServer()
        .requestHandler(router)
        .listen(port)
        .onSuccess { log.info("wallet-vertx listening on port $port") }
        .onFailure { log.error("Failed to start", it) }
}
