package com.walletspring.config

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class OpenApiConfig : WebMvcConfigurer {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Wallet Platform API")
                .version("1.0.0")
                .description("REST API for the Wallet Platform — manage wallets, pockets, credits, spends, and transfers.")
        )
        .servers(listOf(Server().url("/").description("Current server")))

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/redoc", "/redoc.html")
    }
}