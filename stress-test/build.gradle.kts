plugins {
    scala
    id("io.gatling.gradle") version "3.9.5.6"
}

repositories { mavenCentral() }

gatling {
    gatlingVersion = "3.9.5.6"
}
