# CLAUDE.md — Implementing a New Runtime Adapter

This document is for anyone adding a new runtime to expose the
`wallet-domain` library (e.g., Quarkus, Micronaut, gRPC, GraphQL, CLI).

The domain knows nothing about your framework. Your job is to wire the
domain ports, handle authentication, map HTTP ↔ domain, and run the server.

---

## Onion architecture: what goes where

```
┌──────────────────────────────────────────────────┐
│  Inbound adapter  (your framework: REST, gRPC…)  │  ← You write this
│  Outbound adapter (repositories, event bus…)     │  ← You write this
├──────────────────────────────────────────────────┤
│  Application layer  (WalletApplicationService)   │  ← Domain, untouched
│  Domain model       (Wallet, Pocket, Rules…)     │  ← Domain, untouched
└──────────────────────────────────────────────────┘
```

Your adapter **must not** import `WalletApplicationService` directly into
controllers. Route → handler → service → return view.

---

## Step 1 — Add the domain as a dependency

The domain is published to Maven local. Run once from `wallet-domain/`:

```bash
./gradlew publishToMavenLocal
```

In your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.walletdomain:wallet-domain:1.0.0")
    // your framework dependencies…
}
```

---

## Step 2 — Wire the domain beans

Instantiate the application service with an in-memory repo and a policy
loader. This is the **only** place framework DI touches the domain:

```kotlin
// Framework-agnostic wiring (pseudo-code — adapt to your DI)
val repository    = InMemoryWalletRepository()
val policyLoader  = YamlWalletPolicyLoader(classpathDir = "policies/")
val eventBus      = LoggingEventPublisher()        // or Kafka, SNS, etc.
val walletService = WalletApplicationService(repository, eventBus, policyLoader)
```

**Swappable outbound adapters** (all implement domain ports):

| Port | In-memory (provided) | Your production impl |
|---|---|---|
| `WalletRepository` | `InMemoryWalletRepository` | `JpaWalletRepository`, `R2dbcWalletRepository` |
| `DomainEventPublisher` | `NoOpEventPublisher` | `KafkaEventPublisher`, `SnsEventPublisher` |
| `WalletPolicyLoader` | `DefaultWalletPolicyLoader` | `YamlWalletPolicyLoader`, `DbWalletPolicyLoader` |

---

## Step 3 — Implement the OIDC token extractor

The token is an **OIDC Bearer token**. The `sub` claim is the `userId`
passed to the domain. Your adapter must:

1. Validate the JWT signature against the IdP JWKS endpoint
2. Extract `sub` → map to `UserId`
3. Reject with 401 if invalid or expired

### Spring Boot WebFlux example

```kotlin
@Component
class OidcPrincipalExtractor {
    fun extractUserId(jwt: Jwt): UserId = UserId(jwt.subject)
}

// In SecurityConfig:
http.oauth2ResourceServer { it.jwt {} }
```

### Vert.x example

```kotlin
val jwkProvider = JwkProviderBuilder(URI("https://your-idp/.well-known/jwks.json")).build()

router.route().handler { ctx ->
    val token = ctx.request().getHeader("Authorization")?.removePrefix("Bearer ")
        ?: return ctx.fail(401)
    val decoded = JWT.decode(token)
    val verifier = JWT.require(Algorithm.RSA256(jwkProvider.get(decoded.keyId).publicKey as RSAPublicKey, null)).build()
    verifier.verify(decoded)
    ctx.put("userId", decoded.subject)
    ctx.next()
}
```

### Mocking OIDC tokens in tests

See the dedicated section below.

---

## Step 4 — Map REST ↔ domain commands

Create a thin request/response layer. Never expose domain types over the wire.

```
HTTP request body  →  Command (CreatePocketCommand, SpendCommand…)
WalletApplicationService call
View (WalletView, PocketView…)  →  HTTP response body
```

**Recommended endpoint surface:**

| Method | Path | Command/Query |
|---|---|---|
| `POST` | `/wallets` | `CreateWalletCommand` |
| `GET` | `/wallets/{walletId}` | `getWallet()` |
| `POST` | `/wallets/{walletId}/pockets` | `CreatePocketCommand` |
| `POST` | `/wallets/{walletId}/pockets/{pocketId}/credit` | `CreditPocketCommand` |
| `POST` | `/wallets/{walletId}/pockets/{pocketId}/spend` | `SpendCommand` |
| `POST` | `/wallets/{walletId}/transfer` | `TransferCommand` |
| `GET` | `/wallets/{walletId}/pockets/{pocketId}/ledger` | `getLedger()` |
| `POST` | `/wallets/{walletId}/policy/reload` | reload policy from file |

---

## Step 5 — Map domain exceptions to HTTP status codes

All domain exceptions extend `DomainException` (sealed class). Map them in
a single error handler:

```kotlin
fun DomainException.toHttpStatus(): Int = when (this) {
    is DomainException.WalletNotFound    -> 404
    is DomainException.PocketNotFound    -> 404
    is DomainException.WalletAlreadyExists -> 409
    is DomainException.WalletInactive    -> 422
    is DomainException.PocketInactive    -> 422
    is DomainException.InsufficientFunds -> 422
    is DomainException.CategoryNotAllowed -> 422
    is DomainException.InvalidOperation  -> 422   // rules engine denial
}
```

---

## Step 6 — Implement the policy reload endpoint

The policy file lives on the classpath (`policies/wallet-policy-{id}.json`).
After a config change and restart-free reload:

```kotlin
// POST /wallets/{walletId}/policy/reload
fun reloadPolicy(walletId: String): ResponseEntity<*> {
    val id = WalletId.of(walletId)
    policyLoader.reload(id)           // re-read file from disk
    return ResponseEntity.ok(mapOf("reloaded" to walletId))
}
```

The `YamlWalletPolicyLoader` (in the adapter layer, not the domain) reads
from `src/main/resources/policies/` and holds a `ConcurrentHashMap` of
compiled `WalletPolicy` objects.

---

## Mocking OIDC tokens in tests

Since the token is OIDC (not a custom JWT), you have three strategies:

### Strategy A — Spring Security test support (wallet-spring)

Use `@WithMockUser` or `SecurityMockMvcRequestPostProcessors.jwt()`:

```kotlin
@Test
fun `should create wallet when authenticated`() {
    mockMvc.post("/wallets") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"currencyCode":"EUR"}"""
        with(jwt().jwt { it.subject("user-42").claim("roles", listOf("ROLE_USER")) })
    }.andExpect { status { isCreated() } }
}
```

For WebFlux tests, use `SecurityMockServerConfigurers.mockJwt()`:

```kotlin
webTestClient
    .mutateWith(mockJwt().jwt { it.subject("user-42") })
    .post().uri("/wallets")
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{"currencyCode":"EUR"}""")
    .exchange()
    .expectStatus().isCreated
```

No real IdP needed. The JWT is fabricated in-process.

### Strategy B — Embedded WireMock JWKS server (wallet-vertx and integration tests)

Spin up WireMock to serve a JWKS endpoint, sign tokens with your own RSA key:

```kotlin
// Test setup
val keyPair = generateRSAKeyPair()
val wireMock = WireMockServer(8089)
wireMock.stubFor(get("/jwks").willReturn(aResponse()
    .withBody(buildJwksJson(keyPair.public))))

// Sign a test token
val token = JWT.create()
    .withSubject("user-42")
    .withClaim("roles", listOf("ROLE_USER"))
    .withKeyId("test-key-1")
    .sign(Algorithm.RSA256(null, keyPair.private as RSAPrivateKey))

// Use in test
given()
    .header("Authorization", "Bearer $token")
    .post("/wallets")
    .then().statusCode(201)
```

Configure your adapter to point JWKS URL at `http://localhost:8089/jwks`
via `application-test.yml`.

### Strategy C — Domain-layer tests (recommended for most cases)

Most business logic should be tested **without HTTP**. Call
`WalletApplicationService` directly — no tokens needed:

```kotlin
// Pure domain test — fastest, most reliable
val service = WalletApplicationService(InMemoryWalletRepository())
val view = service.createWallet(CreateWalletCommand(UserId("user-42"), "EUR"))
assertThat(view.currency).isEqualTo("EUR")
```

**Use Strategy C for 80% of tests, Strategy A/B for HTTP contract tests.**

---

## Step 7 — Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/wallet-YOUR-RUNTIME.jar app.jar
# Policies directory — mount externally in production
COPY src/main/resources/policies/ policies/
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75", \
  "-jar", "app.jar"]
```

For Vert.x, add `-Dvertx.disableDnsResolver=true` to avoid DNS conflicts
inside Docker.

---

## Checklist before opening a PR

- [ ] Domain module has zero framework imports
- [ ] All `DomainException` subtypes mapped to HTTP status codes
- [ ] OIDC `sub` claim used as `UserId` (not email or name)
- [ ] Policy reload endpoint implemented
- [ ] Unit tests use Strategy C (direct service calls) where possible
- [ ] Integration tests mock OIDC with Strategy A or B
- [ ] Dockerfile present and builds with `docker build .`
- [ ] `README.md` updated with run instructions
