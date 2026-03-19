package com.walletspring.adapter.inbound.security

import com.walletspring.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Date
import javax.crypto.SecretKey

// ---------------------------------------------------------------------------
// JwtAuthFilter — validates Bearer token, sets SecurityContext
// Much simpler than the reactive chain in WebFlux.
// ---------------------------------------------------------------------------

@Component
class JwtAuthFilter(props: JwtProperties) : OncePerRequestFilter() {

    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray())

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")

        if (token != null) {
            runCatching { claims(token) }.getOrNull()
                ?.takeIf { it.expiration.after(Date()) }
                ?.let { c ->
                    @Suppress("UNCHECKED_CAST")
                    val roles = (c["roles"] as? List<String> ?: listOf("ROLE_USER"))
                        .map { SimpleGrantedAuthority(it) }
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(c.subject, null, roles)
                }
        }
        chain.doFilter(request, response)
    }

    fun generateToken(userId: String, roles: List<String> = listOf("ROLE_USER")): String =
        Jwts.builder()
            .subject(userId)
            .claim("roles", roles)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 86_400_000L))
            .signWith(key)
            .compact()

    private fun claims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
