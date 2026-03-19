package com.walletvertx.adapter.inbound

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.vertx.ext.web.RoutingContext
import java.util.Date
import javax.crypto.SecretKey

class JwtHandler(secret: String) {

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun handle(ctx: RoutingContext) {
        val token = ctx.request().getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")

        if (token == null) {
            ctx.response().setStatusCode(401).end("""{"code":"UNAUTHORIZED","message":"Missing token"}""")
            return
        }

        runCatching {
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
            if (claims.expiration.before(Date())) throw IllegalStateException("Token expired")
            ctx.put("userId", claims.subject)
            ctx.next()
        }.onFailure {
            ctx.response().setStatusCode(401).end("""{"code":"UNAUTHORIZED","message":"Invalid token"}""")
        }
    }

    fun generateToken(userId: String): String =
        Jwts.builder()
            .subject(userId)
            .claim("roles", listOf("ROLE_USER"))
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 86_400_000L))
            .signWith(key)
            .compact()
}
