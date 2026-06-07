plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    id("com.diffplug.spotless") version "8.1.0"
}

group = "io.ht"
version = providers.gradleProperty("artifactVersion").orElse("0.0.1-SNAPSHOT").get()
description = "HT build plugin: SUT (kind cluster) lifecycle and shared Spring Boot build helpers"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("build") {
            id = "io.ht.build"
            implementationClass = "io.ht.build.plugin.BuildPlugin"
            displayName = "HT Build Plugin"
            description = "Provides SUT lifecycle tasks (kind cluster) and other shared build helpers for Spring Boot projects."
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    encoding("UTF-8")

    kotlin {
        target("src/**/*.kt")
        ktlint("1.6.0")
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint("1.6.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
