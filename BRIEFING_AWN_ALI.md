# Wallet Platform — Engineering Briefing for Awn Ali

**From:** Waheb (Abdelwaheb Rahmouni)
**Context:** Follow-up to our discussion this morning on the Epassi VP Engineering interview

---

## What was built and why it's relevant to Epassi

This platform is a direct technical response to the challenge we discussed:
managing **multi-pocket benefit wallets** across an acquisition-driven
product landscape where different business units have different rules,
different benefit categories, and different backend runtimes.

---

## The core problem it solves

Epassi operates across multiple European markets with distinct benefit
programs per country (meal vouchers, transport, culture, sport, health,
childcare). Each acquired company likely brought its own wallet model,
its own spending rules, its own tech stack.

This platform was designed with that exact scenario in mind:

- **One domain library** encapsulates all wallet and pocket logic once
- **Multiple runtimes** (Spring Boot and Vert.x) expose the same domain
  without duplicating business logic
- **Per-wallet policy engine** allows country-specific or client-specific
  rules without touching code — just swap the config file

This mirrors the Epassi challenge of unifying acquired platforms while
allowing each market to retain its own benefit constraints.

---

## Architecture walkthrough

### The domain (wallet-domain)

Pure Kotlin, zero framework dependency. Contains:

- `Wallet` aggregate root — owns pockets, currency, lifecycle
- `Pocket` — spending envelope scoped to one or more benefit categories (FOOD, TRANSPORT, CULTURE, SPORT, HEALTH, CHILDCARE, GENERAL)
- `LedgerEntry` — append-only Credit/Debit records per pocket
- `RulesEngine` — pre-authorises every spend and transfer
- `WalletPolicy` — per-wallet declarative config (loaded from file, no restart needed)

Because the domain is a plain JAR, it can be embedded in any runtime —
including legacy services Epassi might have inherited from acquisitions.

### The rules engine — directly relevant to Epassi's multi-market challenge

Every spend attempt passes through a chain of configurable rules:

```
SpendContext → [BenefitCategoryRule] → [MaxTransactionAmountRule]
            → [MerchantBlocklistRule] → [OverspendSplitRule]
            → EngineDecision: Approved | ApprovedWithSplit | Rejected
```

AND semantics: all rules must pass. First denial short-circuits. Each rule
emits structured events (for audit trails, notifications, analytics).

**Overspend split** is the standout feature: if a user's primary pocket
(e.g., meal voucher) is short, the engine automatically covers the
remainder from a designated fallback pocket (e.g., general balance) —
without the user needing to manage it. This maps directly to the kind of
seamless UX Epassi would want across its benefit programs.

### Two runtimes, same domain

| | wallet-spring | wallet-vertx |
|---|---|---|
| Runtime | Spring Boot 3 + WebFlux | Vert.x 4 |
| Model | Reactive (Project Reactor) | Non-blocking event loop |
| Thread model | Virtual threads (Java 21) | 2×CPU event loop threads |
| Expected throughput | High | Higher |
| Expected memory | ~200MB RSS | ~80MB RSS |

The Gatling benchmark quantifies this with real numbers:
throughput (req/s), p99 latency, memory footprint, CPU under sustained load.

The point is not that Spring is bad — it's that **the same domain works in
both**, meaning Epassi could run the domain in whatever runtime a given
acquired product already uses, without rewriting business logic.

### OIDC token as identity

The `Authorization: Bearer <token>` OIDC JWT's `sub` claim maps directly
to `UserId`. No user management inside the wallet service. This means the
service integrates cleanly with whatever IdP Epassi uses (Keycloak, Azure
AD, Auth0) across its different markets and subsidiaries.

---

## What this demonstrates technically

**Domain-Driven Design at scale**
The `Wallet`, `Pocket`, `LedgerEntry`, and `BenefitCategory` model is rich
enough to handle multi-country benefit programs but stable enough to be
shared as a library across runtimes.

**Hexagonal / onion architecture**
The domain has no knowledge of Spring or Vert.x. Swapping persistence
(currently in-memory, ready for PostgreSQL/R2DBC) is a one-class change.
This is precisely the pattern needed when integrating acquired systems.

**Configurable policy engine without code changes**
Operators can change spending caps, blocked merchants, overspend fallback
pockets, and transfer limits per wallet via a JSON file — then call a
reload endpoint without restarting the service. For a multi-market
operator managing thousands of employer contracts with different benefit
rules, this matters.

**Test coverage strategy**
- Domain rules tested purely (no HTTP, no containers, fast feedback)
- HTTP layer tested with mocked OIDC tokens (no real IdP needed)
- Gatling stress tests with real concurrent load showing runtime differences

---

## What would be added for production at Epassi scale

This is a demonstration platform. For production, the next layer would include:

- **PostgreSQL persistence** — swap `InMemoryWalletRepository` for
  R2DBC-backed repository (Vert.x) or Spring Data R2DBC (Spring Boot)
- **Event streaming** — replace `NoOpEventPublisher` with Kafka for
  cross-service event propagation (transaction monitoring, reporting)
- **Distributed policy store** — replace file-based loader with a
  database-backed `WalletPolicyLoader` for real-time rule changes
- **Rate limiting** per wallet/user at the API gateway layer
- **Multi-tenancy** — wallet namespace isolation per employer/market

The architecture is explicitly designed to make each of these a
**one-adapter change** without touching the domain.

---

## Questions this platform can answer directly

- "How do you handle different benefit rules per employer or market?" → Per-wallet policy engine
- "How do you avoid duplicating business logic across runtimes?" → Domain-as-library pattern
- "How do you ensure an acquired company's backend can plug in?" → Port/adapter pattern, any runtime can embed the domain JAR
- "How do you test business rules without spinning up infrastructure?" → Pure domain tests, no Spring context, no database
- "How do you manage OIDC identity across different IdPs?" → `sub` claim abstraction, IdP-agnostic UserId

---

*Full source: wallet-domain, wallet-spring, wallet-vertx*
*Tech stack: Kotlin, Gradle, Spring Boot 3, Vert.x 4, Gatling, OIDC/JWT*
