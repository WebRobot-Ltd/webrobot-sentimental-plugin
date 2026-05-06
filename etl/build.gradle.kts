plugins {
    id("scala")
    id("java-library")
}

group   = "eu.webrobot.plugins"
version = "0.1.0"

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
    compileOnly("com.github.WebRobot-Ltd:webrobot-plugin-sdk:v0.2.1")
    compileOnly("org.scala-lang:scala-library:$scalaFullV")

    testImplementation("org.scalatest:scalatest_$scalaV:3.2.18")
    testImplementation("org.scala-lang:scala-library:$scalaFullV")
}

tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.test { useJUnitPlatform() }
