package wallet

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

// ---------------------------------------------------------------------------
// WalletSimulation — stress test targeting both runtimes side by side.
//
// Run against Spring:  BASE_URL=http://localhost:8080 ./gradlew gatlingRun
// Run against Vert.x:  BASE_URL=http://localhost:8081 ./gradlew gatlingRun
// Run both at once:    ./run-stress.sh
//
// Measures: throughput (req/s), p50/p95/p99 latency, error rate
// ---------------------------------------------------------------------------

class WalletSimulation extends Simulation {

  // -------------------------------------------------------------------------
  // Config from env — defaults target Spring
  // -------------------------------------------------------------------------

  val baseUrl   = sys.env.getOrElse("BASE_URL",     "http://localhost:8080")
  val jwtSecret = sys.env.getOrElse("JWT_SECRET",   "wallet-super-secret-key-32chars!!")
  val users     = sys.env.getOrElse("USERS",        "50").toInt
  val duration  = sys.env.getOrElse("DURATION_SEC", "60").toInt

  // Pre-generate a fixed JWT for load test user (avoid token endpoint being a bottleneck)
  // In a real scenario, generate via /auth/token and cache
  val testToken = generateTestToken(jwtSecret)

  val httpConf = http
    .baseUrl(baseUrl)
    .header("Authorization", s"Bearer $testToken")
    .header("Content-Type", "application/json")
    .acceptHeader("application/json")
    .shareConnections          // simulate realistic connection pool

  // -------------------------------------------------------------------------
  // Scenario: full wallet lifecycle
  //   1. Create wallet
  //   2. Create two pockets (food + general)
  //   3. Top-up both pockets
  //   4. Spend from food pocket (happy path)
  //   5. Transfer between pockets
  //   6. Read ledger
  // -------------------------------------------------------------------------

  val walletLifecycle = scenario("WalletLifecycle")
    .exec(
      http("01_CreateWallet")
        .post("/wallets")
        .body(StringBody("""{"currencyCode":"EUR"}"""))
        .check(status.is(201))
        .check(jsonPath("$.id").saveAs("walletId"))
    )
    .pause(50.milliseconds)

    .exec(
      http("02_CreateFoodPocket")
        .post("/wallets/#{walletId}/pockets")
        .body(StringBody("""{"name":"Food","allowedBenefits":["FOOD"]}"""))
        .check(status.is(201))
        .check(jsonPath("$.id").saveAs("foodPocketId"))
    )
    .pause(50.milliseconds)

    .exec(
      http("03_CreateGeneralPocket")
        .post("/wallets/#{walletId}/pockets")
        .body(StringBody("""{"name":"General","allowedBenefits":["GENERAL"]}"""))
        .check(status.is(201))
        .check(jsonPath("$.id").saveAs("generalPocketId"))
    )
    .pause(50.milliseconds)

    .exec(
      http("04_TopUpFood")
        .post("/wallets/#{walletId}/pockets/#{foodPocketId}/credit")
        .body(StringBody("""{"amount":10000,"currencyCode":"EUR","source":"TOP_UP","reference":"TOPUP-FOOD"}"""))
        .check(status.is(200))
    )
    .pause(50.milliseconds)

    .exec(
      http("05_TopUpGeneral")
        .post("/wallets/#{walletId}/pockets/#{generalPocketId}/credit")
        .body(StringBody("""{"amount":20000,"currencyCode":"EUR","source":"TOP_UP","reference":"TOPUP-GENERAL"}"""))
        .check(status.is(200))
    )
    .pause(50.milliseconds)

    .repeat(5) {
      exec(
        http("06_Spend")
          .post("/wallets/#{walletId}/pockets/#{foodPocketId}/spend")
          .body(StringBody(s"""{"amount":500,"currencyCode":"EUR","merchant":"Carrefour","benefitCategory":"FOOD","reference":"SPEND-${Random.alphanumeric.take(8).mkString}"}"""))
          .check(status.in(200, 422))  // 422 = insufficient funds after repeated spends
      )
      .pause(20.milliseconds)
    }

    .exec(
      http("07_Transfer")
        .post("/wallets/#{walletId}/transfer")
        .body(StringBody("""{"fromPocketId":"#{generalPocketId}","toPocketId":"#{foodPocketId}","amount":5000,"currencyCode":"EUR","reference":"XFER-REFILL"}"""))
        .check(status.is(200))
    )
    .pause(50.milliseconds)

    .exec(
      http("08_GetLedger")
        .get("/wallets/#{walletId}/pockets/#{foodPocketId}/ledger")
        .check(status.is(200))
    )

    .exec(
      http("09_GetWallet")
        .get("/wallets/#{walletId}")
        .check(status.is(200))
    )

  // -------------------------------------------------------------------------
  // Read-heavy scenario — simulates dashboards / balance checks
  // -------------------------------------------------------------------------

  val readHeavy = scenario("ReadHeavy")
    .exec(
      http("R1_CreateWallet")
        .post("/wallets")
        .body(StringBody("""{"currencyCode":"EUR"}"""))
        .check(status.is(201))
        .check(jsonPath("$.id").saveAs("walletId"))
    )
    .exec(
      http("R2_CreatePocket")
        .post("/wallets/#{walletId}/pockets")
        .body(StringBody("""{"name":"Main","allowedBenefits":["GENERAL"]}"""))
        .check(status.is(201))
        .check(jsonPath("$.id").saveAs("pocketId"))
    )
    .exec(
      http("R3_TopUp")
        .post("/wallets/#{walletId}/pockets/#{pocketId}/credit")
        .body(StringBody("""{"amount":50000,"currencyCode":"EUR","source":"TOP_UP","reference":"INIT"}"""))
        .check(status.is(200))
    )
    .repeat(20) {
      exec(
        http("R4_GetWallet")
          .get("/wallets/#{walletId}")
          .check(status.is(200))
      )
      .pause(10.milliseconds)
    }

  // -------------------------------------------------------------------------
  // Load shape: ramp to target users, hold, then ramp down
  // -------------------------------------------------------------------------

  setUp(
    walletLifecycle.inject(
      rampUsers(users)      over (10.seconds),
      constantUsersPerSec(users / 2.0) during (duration.seconds),
    ),
    readHeavy.inject(
      rampUsers(users / 2)  over (10.seconds),
      constantUsersPerSec(users / 4.0) during (duration.seconds),
    ),
  )
    .protocols(httpConf)
    .assertions(
      global.responseTime.percentile(99).lte(500),   // p99 < 500ms
      global.successfulRequests.percent.gte(95),      // 95%+ success rate
    )

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def generateTestToken(secret: String): String = {
    // Simple HS256 JWT — avoids depending on jjwt in Scala test
    import java.util.Base64
    import javax.crypto.Mac
    import javax.crypto.spec.SecretKeySpec
    val header  = Base64.getUrlEncoder.withoutPadding.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes)
    val exp     = System.currentTimeMillis() / 1000 + 86400
    val payload = Base64.getUrlEncoder.withoutPadding.encodeToString(s"""{"sub":"stress-user","roles":["ROLE_USER"],"exp":$exp}""".getBytes)
    val mac     = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes, "HmacSHA256"))
    val sig     = Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(s"$header.$payload".getBytes))
    s"$header.$payload.$sig"
  }
}
