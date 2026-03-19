import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.walletvertx"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
}

val vertxVersion = "4.5.7"

dependencies {
    implementation("com.walletdomain:wallet-domain:1.0.0")

    // Vert.x core + web + auth JWT
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-auth-jwt:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-health-check:$vertxVersion")
    implementation("io.vertx:vertx-micrometer-metrics:$vertxVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.4")

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    // JWT validation
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Test
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

tasks.withType<KotlinCompile> {
    kotlinOptions { jvmTarget = "21" }
}

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("com.walletvertx.MainKt")
}

tasks.shadowJar {
    archiveFileName.set("wallet-vertx.jar")
    mergeServiceFiles()
}
