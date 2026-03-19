# syntax=docker/dockerfile:1.4
# ---------------------------------------------------------------------------
# wallet-spring — full multi-stage build inside Docker
#
# ~/.m2 is shared via a named Docker volume (wallet-m2-cache).
# Populate it once with:
#   docker compose run --rm m2-init
# Then build normally:
#   docker compose up --build
# ---------------------------------------------------------------------------

FROM gradle:8.7-jdk21 AS builder
WORKDIR /build

COPY build.gradle.kts settings.gradle.kts ./
COPY src/ src/

# Use the named cache volume mounted at /root/.m2
# This volume is pre-populated by the m2-init service in docker-compose.yml
RUN --mount=type=cache,target=/root/.m2,id=wallet-m2-cache \
    gradle bootJar --no-daemon -q

# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/build/libs/wallet-spring.jar app.jar
COPY src/main/resources/policies/ policies/

EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
