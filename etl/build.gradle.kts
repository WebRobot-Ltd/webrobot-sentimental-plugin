plugins {
    id("scala")
    id("java-library")
}

group   = "eu.webrobot.plugins"
version = "0.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

val scalaV     = "2.13"
val scalaFullV = "2.13.12"

dependencies {
    // Public Plugin SDK via JitPack — partners need no auth to fetch
    // Must match the LLM/stage contract the DEPLOYED Spark engine provides. v0.3.0 introduced an
    // LlmService the running engine does not wire, so SentimentAnalyzeStage throws at runtime
    // ("LlmService not provided by this engine build"). v0.2.1 is the engine-compatible contract
    // (the working committed jar used it). Do NOT bump without upgrading the Spark engine too.
    compileOnly("com.github.WebRobot-Ltd:webrobot-plugin-sdk:v0.2.1")
    compileOnly("org.scala-lang:scala-library:$scalaFullV")

    testImplementation("org.scalatest:scalatest_$scalaV:3.2.18")
    testImplementation("org.scala-lang:scala-library:$scalaFullV")
}

tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.test { useJUnitPlatform() }
