plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "ru.finnetrolle.tengu"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "dev.detekt")

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        val configs = mutableListOf<Any>("$rootDir/config/detekt/detekt.yml")
        if (name == "toon") configs += "$rootDir/config/detekt/detekt-toon.yml"
        config.setFrom(configs)
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            html.required.set(true)
            checkstyle.required.set(true)
        }
    }
}
