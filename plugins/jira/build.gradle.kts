plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":toolkit"))
    implementation(libs.ktor.client.cio)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
}
