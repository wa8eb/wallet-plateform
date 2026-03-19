plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    `maven-publish`
}

group = "com.walletdomain"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Zero framework dependencies — pure domain
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
