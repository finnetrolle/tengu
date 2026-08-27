plugins {
    // тулчейны (21) авто-провижинятся, если JDK нет локально — Gradle скачает Temurin через foojay
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tengu"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":protocol")
include(":toon")
include(":toolkit")
include(":plugins:jira")
include(":server")
include(":cli")
