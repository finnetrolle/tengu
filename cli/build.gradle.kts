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
    implementation(project(":toon"))
    implementation(libs.clikt)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.nop) // stderr CLI должен оставаться пустым (AXI §6)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("ru.finnetrolle.tengu.cli.MainKt")
    applicationName = "tengu"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",   // агенты читают пайп: вывод всегда UTF-8
        "-Dstderr.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED", // jna (mordant) без JVM-предупреждений
    )
}
