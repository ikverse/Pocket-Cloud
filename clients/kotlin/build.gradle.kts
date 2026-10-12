import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
}

group = "cloud.pocket"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Amazon's S3 client for Kotlin (JVM and Android). connect() returns its S3Client.
    api("aws.sdk.kotlin:s3:1.9.17")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.test {
    useJUnit()
    // The tests start a real Pocket Cloud server from server/tests/serve_for_tests.py.
    systemProperty("pocket.root", rootProject.projectDir.resolve("../..").canonicalPath)
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
