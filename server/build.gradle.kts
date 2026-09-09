plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":toolkit"))
    implementation(project(":plugins:jira"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.cio)
    implementation(libs.logback.classic)
    implementation(libs.logstash.encoder)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("ru.finnetrolle.tengu.server.MainKt")
}

tasks.test {
    dependsOn(tasks.installDist)
    val loggingFixtures = layout.buildDirectory.dir("logging-fixtures")
    outputs.dir(loggingFixtures)
    systemProperty("tengu.test.fixtures", loggingFixtures.get().asFile.absolutePath)
    systemProperty("tengu.test.distribution", layout.buildDirectory.dir("install/server").get().asFile.absolutePath)
}
