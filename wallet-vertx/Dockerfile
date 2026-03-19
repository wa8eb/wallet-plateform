# syntax=docker/dockerfile:1.4
# ---------------------------------------------------------------------------
# wallet-vertx — full multi-stage build inside Docker
# Same ~/.m2 cache volume as wallet-spring.
# ---------------------------------------------------------------------------

FROM gradle:8.7-jdk21 AS builder
WORKDIR /build

COPY build.gradle.kts settings.gradle.kts ./
COPY src/ src/

RUN --mount=type=cache,target=/root/.m2,id=wallet-m2-cache \
    gradle shadowJar --no-daemon -q

# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/build/libs/wallet-vertx.jar app.jar
COPY src/main/resources/policies/ policies/

EXPOSE 8081
ENV PORT=8081
ENV JWT_SECRET=wallet-super-secret-key-32chars!!

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75", \
  "-Dvertx.disableDnsResolver=true", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
