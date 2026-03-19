# Wallet Platform — Monorepo Overview

A production-grade **benefit wallet API** built with pure DDD and onion
architecture. The domain is framework-free Kotlin. Two runtime adapters
expose the same domain over REST: one with Spring Boot WebMVC, one with
Vert.x — enabling a direct performance comparison.

---

## Repository structure

```
wallet-domain/          Pure Kotlin domain library (no framework)
wallet-spring/          Spring Boot 3 + WebMVC adapter  (port 8080)
wallet-vertx/           Vert.x 4 adapter                (port 8081)
stress-test/            Gatling performance benchmarks
infra/                  Prometheus + Grafana configuration
```

---

## Concepts

| Term | Definition |
|---|---|
| **Wallet** | Aggregate root. Belongs to one user (identified by OIDC `sub`). Holds one currency. |
| **Pocket** | Named spending envelope within a wallet. Scoped to one or more benefit categories. |
| **Benefit category** | What a pocket can be spent on: FOOD, TRANSPORT, CULTURE, SPORT, HEALTH, CHILDCARE, GENERAL. |
| **Ledger** | Append-only list of Credit and Debit entries per pocket. |
| **Policy** | Per-wallet YAML config that drives the rules engine. Loaded at startup, reloadable. |
| **Rules engine** | Pre-authorises every spend and transfer. AND semantics — all rules must pass. |

---

## Architecture

```
HTTP request (OIDC Bearer token)
    │
    ▼  [Inbound adapter — Spring or Vert.x]
    │  • Validate JWT (OIDC sub → UserId)
    │  • Map HTTP body → Command
    │
    ▼  [Application Service — WalletApplicationService]
    │  • Load wallet from repository
    │  • Load policy from WalletPolicyLoader
    │  • Run RulesEngine (spend / transfer pre-auth)
    │  • Execute domain command (Wallet / Pocket aggregates)
    │  • Publish domain events
    │  • Save updated wallet
    │  • Return View
    │
    ▼  [Outbound adapters]
       • WalletRepository  →  InMemoryWalletRepository (swap for JPA/R2DBC)
       • DomainEventPublisher  →  NoOpEventPublisher (swap for Kafka/SNS)
       • WalletPolicyLoader  →  YamlWalletPolicyLoader
```

---

## Rules engine

Every spend and transfer is pre-authorised by a chain of rules loaded from
`src/main/resources/policies/wallet-policy-{walletId}.json`.

**Available spend rules:**
- `BenefitCategoryRule` — pocket must cover the requested category
- `MaxTransactionAmountRule` — single-spend cap
- `MerchantBlocklistRule` — deny specific merchants
- `OverspendSplitRule` — cover shortfall from a fallback pocket
- `LowBalanceNotifyRule` — emit alert when balance drops below threshold

**Available transfer rules:**
- `TransferSufficientFundsRule` — source pocket must have enough funds
- `MaxTransferAmountRule` — per-transfer cap
- `TransferBenefitCompatibilityRule` — pockets must share a benefit category
- `LargeTransferNotifyRule` — emit alert on large transfers

**To add a new rule:** see [`wallet-domain/CLAUDE.md`](wallet-domain/CLAUDE.md).

**To add a new adapter:** see [`wallet-domain/CLAUDE_ADAPTERS.md`](wallet-domain/CLAUDE_ADAPTERS.md).

---

## Policy file format

Place at `src/main/resources/policy-global.json`:

```json
{
  "_comment": "Global instance-wide policy — applies to ALL wallets and ALL pockets.",
  "_reload": "POST /admin/policy/reload to apply changes without restart.",
  "spend": {
    "currency": "EUR",
    "maxTransactionAmount": 50000,
    "blockedMerchants": ["CasinoXYZ"],
    "overspendFallbackPocketId": "660e8400-e29b-41d4-a716-446655440001",
    "lowBalanceNotifyThreshold": 1000
  },
  "transfer": {
    "currency": "EUR",
    "maxTransferAmount": 100000,
    "enforceBenefitCompatibility": true,
    "largeTransferNotifyThreshold": 20000
  }
}
```

Wallets without a policy file use permissive defaults (no caps, no blocks).

---

## Authentication

The API expects an **OIDC Bearer token** in the `Authorization` header.
The `sub` claim is used as the `userId`. No user management is done inside
this service — identity is fully delegated to your IdP (Keycloak, Auth0,
Azure AD, etc.).

### Testing without a real IdP

**Spring Boot tests:** use `SecurityMockServerConfigurers.mockJwt()` — no
IdP needed, token is fabricated in-process.

**Vert.x / integration tests:** use WireMock to serve a JWKS endpoint, sign
tokens with your own RSA key pair.

**Domain-only tests (80% of cases):** call `WalletApplicationService`
directly — no token involved.

Full details in [`CLAUDE_ADAPTERS.md`](wallet-domain/CLAUDE_ADAPTERS.md).

---

## Quick start

### Build the domain library

```bash
cd wallet-domain
./gradlew test publishToMavenLocal
```

### Run Spring Boot

```bash
cd wallet-spring
./gradlew bootRun
# API available at http://localhost:8080
# Redocly UI:  http://localhost:8080/redoc
# OpenAPI JSON: http://localhost:8080/v3/api-docs
# Swagger UI:  http://localhost:8080/swagger-ui.html
```

### Run Vert.x

```bash
cd wallet-vertx
./gradlew run
# API available at http://localhost:8081
# Redocly UI:  http://localhost:8081/redoc
# OpenAPI YAML: http://localhost:8081/openapi.yaml
```

### Docker

```bash
# Spring Boot
docker build -t wallet-spring ./wallet-spring
docker run -p 8080:8080 wallet-spring

# Vert.x
docker build -t wallet-vertx ./wallet-vertx
docker run -p 8081:8081 wallet-vertx
```

---

## API documentation

Interactive API docs are served by both adapters via [Redocly](https://redocly.com/):

| Adapter | Redocly UI | Spec |
|---|---|---|
| Spring Boot | `http://localhost:8080/redoc` | `GET /v3/api-docs` (JSON, auto-generated) |
| Vert.x | `http://localhost:8081/redoc` | `GET /openapi.yaml` (YAML, bundled in jar) |

Both docs endpoints are public (no auth token required).

---

## REST API

All endpoints require `Authorization: Bearer <jwt-token>` unless noted.
Amounts are always in **minor units** (e.g., 5000 = 50.00 EUR).

### Auth (public)

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/token` | Generate a JWT for testing (`{"userId": "u1"}`) |

### Wallets

| Method | Path | Description |
|---|---|---|
| `POST` | `/wallets` | Create wallet for the authenticated user |
| `GET` | `/wallets/{id}` | Get wallet with all pockets and balances |
| `DELETE` | `/wallets/{id}` | Close (deactivate) a wallet |

### Pockets

| Method | Path | Description |
|---|---|---|
| `POST` | `/wallets/{id}/pockets` | Create a new pocket |
| `GET` | `/wallets/{id}/pockets/{pid}` | Get a pocket |
| `DELETE` | `/wallets/{id}/pockets/{pid}` | Deactivate a pocket |

### Transactions

| Method | Path | Description |
|---|---|---|
| `POST` | `/wallets/{id}/pockets/{pid}/credit` | Top-up a pocket |
| `POST` | `/wallets/{id}/pockets/{pid}/spend` | Pre-authorised spend |
| `POST` | `/wallets/{id}/transfer` | Transfer between two pockets |
| `GET` | `/wallets/{id}/pockets/{pid}/ledger` | Full ledger for a pocket |

### Admin

| Method | Path | Description |
|---|---|---|
| `GET` | `/admin/policy/global` | Get the active global policy |
| `POST` | `/admin/policy/global/reload` | Hot-reload global policy from disk |
| `POST` | `/wallets/{id}/policy/reload` | Reload wallet-specific policy |

---

## Stress test benchmark

Gatling scenarios hitting both runtimes simultaneously:

```bash
cd stress-test
./gradlew gatlingRun
```

The benchmark measures **throughput (req/s), p99 latency, memory footprint,
and CPU under load** for a mixed scenario of wallet creation, pocket credit,
and spend operations.

Expected outcome: Vert.x sustains significantly higher throughput at lower
memory with a fraction of the thread count, demonstrating the advantage of
a reactive, non-blocking event loop for I/O-bound workloads.

---

## Technology decisions

| Decision | Rationale |
|---|---|
| Pure Kotlin domain | Zero framework coupling, maximum testability |
| Sealed classes for events/exceptions | Exhaustive pattern matching, no missed cases |
| Immutable aggregates (data class) | Thread safety without locks; easy event sourcing migration |
| In-memory repository | Swap cost is one line — plug JPA or R2DBC without touching the domain |
| AND rule composition | Predictable, auditable — every rule must pass, first denial wins |
| Policy file per wallet | Operators can tune individual wallets without code changes |
| OIDC `sub` as UserId | Standard, IdP-agnostic, no user table required |

---

## Module dependency graph

```
wallet-spring  ──┐
                 ├──►  wallet-domain  (JAR via mavenLocal)
wallet-vertx   ──┘
```

The domain has **no knowledge** of either runtime. Both runtimes are
independently deployable and independently scalable.
