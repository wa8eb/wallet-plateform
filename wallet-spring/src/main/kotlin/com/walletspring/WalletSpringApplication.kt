package com.walletspring

import com.fasterxml.jackson.databind.ObjectMapper
import com.walletdomain.application.usecase.InMemoryWalletRepository
import com.walletdomain.application.usecase.WalletApplicationService
import com.walletdomain.domain.port.DomainEventPublisher
import com.walletdomain.domain.port.NoOpEventPublisher
import com.walletdomain.domain.rules.policy.DefaultWalletPolicyLoader
import com.walletspring.adapter.outbound.JsonGlobalPolicyLoader
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties::class, PolicyProperties::class)
class WalletSpringApplication

fun main(args: Array<String>) {
    runApplication<WalletSpringApplication>(*args)
}

@Configuration
class DomainConfiguration {

    @Bean
    fun walletRepository() = InMemoryWalletRepository()

    @Bean
    fun eventPublisher(): DomainEventPublisher = NoOpEventPublisher

    @Bean
    fun walletPolicyLoader() = DefaultWalletPolicyLoader()

    @Bean
    fun globalPolicyLoader(mapper: ObjectMapper, props: PolicyProperties) =
        JsonGlobalPolicyLoader(mapper, props.globalPolicyFile)

    @Bean
    fun walletApplicationService(
        walletRepository: InMemoryWalletRepository,
        eventPublisher: DomainEventPublisher,
        walletPolicyLoader: DefaultWalletPolicyLoader,
        globalPolicyLoader: JsonGlobalPolicyLoader,
    ) = WalletApplicationService(
        walletRepository   = walletRepository,
        eventPublisher     = eventPublisher,
        walletPolicyLoader = walletPolicyLoader,
        globalPolicyLoader = globalPolicyLoader,
    )
}

@ConfigurationProperties(prefix = "wallet.jwt")
data class JwtProperties(
    val secret: String = "wallet-super-secret-key-32chars!!",
    val expirationMs: Long = 86_400_000L,
)

@ConfigurationProperties(prefix = "wallet.policy")
data class PolicyProperties(
    /** Filesystem path or classpath resource name for the global policy file. */
    val globalPolicyFile: String = "policy-global.json",
)
