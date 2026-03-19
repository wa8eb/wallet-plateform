# Running the Wallet Platform

## Prerequisites

| Tool | Min version | Install |
|---|---|---|
| JDK | 21 | `sdk install java 21-tem` |
| Docker + Compose | 24+ | [docker.com](https://docker.com) |

---

## Option A — Run locally (fastest for development)

### 1. Build and publish the domain library

The domain is a plain JAR. Both runtimes depend on it via `mavenLocal()`.

```bash
cd wallet-domain
./gradlew clean test publishToMavenLocal
```

You should see all tests pass. If any fail, stop here and fix them first.

### 2. Run wallet-spring

```bash
cd wallet-spring
./gradlew bootRun
# Listening on http://localhost:8080
```

### 3. Run wallet-vertx (separate terminal)

```bash
cd wallet-vertx
./gradlew run
# Listening on http://localhost:8081
```

### 4. Get a test token

Both runtimes expose `/auth/token` (no auth required):

```bash
curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"user-1"}' | jq .
# {"token":"eyJ..."}
export TOKEN="eyJ..."
```

### 5. Smoke test

```bash
# Create wallet
curl -s -X POST http://localhost:8080/wallets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"currencyCode":"EUR"}' | jq .

# Create pocket
WALLET_ID="<id from above>"
curl -s -X POST http://localhost:8080/wallets/$WALLET_ID/pockets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Food Budget","allowedBenefits":["FOOD"]}' | jq .

# Top up
POCKET_ID="<id from above>"
curl -s -X POST http://localhost:8080/wallets/$WALLET_ID/pockets/$POCKET_ID/credit \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":10000,"currencyCode":"EUR","source":"TOP_UP","reference":"INIT-1"}' | jq .

# Spend
curl -s -X POST http://localhost:8080/wallets/$WALLET_ID/pockets/$POCKET_ID/spend \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":2500,"currencyCode":"EUR","merchant":"Carrefour","benefitCategory":"FOOD","reference":"SPEND-1"}' | jq .
```

Repeat with port `8081` to test Vert.x — identical API surface, same responses.

---

## Option B — Docker Compose (production-like)

> **Important:** both services depend on `wallet-domain` being published to your
> local Maven repository (`~/.m2`). The `docker-compose.yml` mounts `~/.m2` into
> the builder stage so Gradle can resolve it. If you skip step 1 the build will
> fail with a dependency resolution error.

### 1. Publish the domain to mavenLocal (required — do this once)

```bash
cd wallet-domain
./gradlew clean test publishToMavenLocal
```

### 2. Start everything

```bash
# From the root directory (first run takes a few minutes — downloads Gradle + deps)
docker compose --profile init run --rm m2-init
docker compose up --build
```

This starts:
- `wallet-spring` on http://localhost:8080
- `wallet-vertx` on http://localhost:8081
- `Prometheus` on http://localhost:9090
- `Grafana` on http://localhost:3000 (login: admin / wallet)

The healthcheck waits up to 30s for each service to be ready before
Prometheus starts scraping.

### 3. Check health

```bash
curl http://localhost:8080/actuator/health   # Spring
curl http://localhost:8081/health            # Vert.x
```

### 4. Stop

```bash
docker compose down
```

---

## Option C — IntelliJ (recommended for development)

1. Open the root folder in IntelliJ
2. IntelliJ will detect the three Gradle projects automatically
3. Run `wallet-domain` tests first: right-click `src/test` → **Run Tests**
4. Run `WalletSpringApplication.main()` (Spring Boot run config auto-detected)
5. Run `MainKt.main()` for Vert.x (create a Kotlin run config pointing at `com.walletvertx.MainKt`)
6. Set env var `JWT_SECRET=wallet-super-secret-key-32chars!!` in both run configs

### OIDC token mocking in tests (IntelliJ)

For Spring controller tests, use the provided `mockJwt()` helper:

```kotlin
@SpringBootTest(webEnvironment = RANDOM_PORT)
class WalletControllerTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `create wallet returns 201`() {
        mvc.perform(
            post("/wallets")
                .contentType(APPLICATION_JSON)
                .content("""{"currencyCode":"EUR"}""")
                .with(jwt().jwt { it.subject("user-test") })
        ).andExpect(status().isCreated)
    }
}
```

No Keycloak, no WireMock, no real IdP needed for unit/integration tests.

---

## Running the stress tests

### Prerequisites

Both runtimes must be running (Option A or B above).

### Run both simulations and compare

```bash
cd stress-test
./run-stress.sh
```

This runs Gatling against Spring first, then Vert.x, with identical load.

### Custom parameters

```bash
USERS=100 DURATION_SEC=120 ./run-stress.sh
```

### Run against one runtime only

```bash
cd stress-test

# Spring only
BASE_URL=http://localhost:8080 ./gradlew gatlingRun

# Vert.x only
BASE_URL=http://localhost:8081 ./gradlew gatlingRun
```

### Read the results

After each run, Gatling generates an HTML report:

```
stress-test/build/reports/gatling/<simulation-timestamp>/index.html
```

Open it in your browser. Key charts:

| Chart | What to compare |
|---|---|
| **Response Time Percentiles** | p99 Spring vs p99 Vert.x — expect Vert.x lower under load |
| **Requests/s** | Peak throughput before errors start climbing |
| **Active Users** | How each runtime handles concurrency ramp |
| **Errors** | Rate and type — 422s are domain errors (expected), 5xx are infra failures |

### Live metrics during the test

With Docker Compose running, Grafana auto-discovers both services.
Open http://localhost:3000, go to **Explore → Prometheus**, and query:

```promql
# Requests per second
rate(http_server_requests_seconds_count[30s])

# JVM memory
jvm_memory_used_bytes{area="heap"}

# p99 response time
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[30s]))
```

### What to expect

| Metric | Spring (coroutines) | Vert.x |
|---|---|---|
| Throughput | ~1500–2500 req/s | ~3000–5000 req/s |
| p99 latency | 30–80ms | 10–40ms |
| Heap at load | ~180–250MB | ~60–100MB |
| Thread count | 50–200 | 4–8 event loop |

These are indicative figures on a laptop (8-core, 16GB).
Results depend heavily on hardware. The ratio matters more than absolutes.

> **Why the difference?** Vert.x runs on 2×CPU event loop threads.
> Every request is non-blocking from network all the way to the in-memory store.
> Spring with coroutines dispatches to a thread pool (Dispatchers.IO) — still
> non-blocking at the HTTP layer but uses more threads for the domain call.
> With a real database, the gap narrows because both block on I/O equally.

---

## Troubleshooting

**Docker build fails: `failed to compute cache key: not found`**
→ A `COPY` in the Dockerfile references a path that doesn't exist in the build context.
Make sure `src/main/resources/policies/` exists in both `wallet-spring` and `wallet-vertx`
(it ships with a `.gitkeep` and an example policy). If you cloned without those files,
create the directory: `mkdir -p wallet-spring/src/main/resources/policies`

**`wallet-domain` not found by wallet-spring/wallet-vertx**
→ Run `./gradlew publishToMavenLocal` in `wallet-domain` first.

**Port 8080/8081 already in use**
→ `lsof -i :8080` to find the process, or change `server.port` in `application.yml`.

**Docker build fails with "package not found"**
→ The Dockerfiles copy from mavenLocal. Run `./gradlew publishToMavenLocal` on the host first, then rebuild.

**Gatling `NoClassDefFoundError`**
→ Run `./gradlew clean` in `stress-test` then retry.

**Token rejected (401)**
→ Both runtimes default to secret `wallet-super-secret-key-32chars!!`. Make sure the token was generated with the same secret. Check the `JWT_SECRET` env var if using Docker.
