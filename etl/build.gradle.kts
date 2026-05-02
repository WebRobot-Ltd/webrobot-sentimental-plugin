plugins {
    id("scala")
    id("java-library")
}

group   = "eu.webrobot.plugins"
version = "0.1.0"

repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url  = uri("https://maven.pkg.github.com/WebRobot-Ltd/webrobot-plugin-sdk")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: "webroboteu"
            password = System.getenv("GITHUB_TOKEN")
                ?: throw GradleException("GITHUB_TOKEN env var required to download webrobot-plugin-sdk")
        }
    }
}

val scalaV     = "2.12"
val scalaFullV = "2.12.18"

dependencies {
    compileOnly("eu.webrobot:webrobot-plugin-sdk:latest.release")
    compileOnly("org.scala-lang:scala-library:$scalaFullV")

    testImplementation("org.scalatest:scalatest_$scalaV:3.2.18")
    testImplementation("org.scala-lang:scala-library:$scalaFullV")
}

tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.test { useJUnitPlatform() }
