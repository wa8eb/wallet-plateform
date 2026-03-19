package com.walletspring.adapter.inbound.rest

import com.walletspring.adapter.inbound.security.JwtAuthFilter
import org.springframework.web.bind.annotation.*

@RestController
class AuthController(private val jwtFilter: JwtAuthFilter) {

    @PostMapping("/auth/token")
    fun token(@RequestBody body: Map<String, String>): Map<String, String> {
        val userId = body["userId"] ?: "demo"
        return mapOf("token" to jwtFilter.generateToken(userId))
    }
}
