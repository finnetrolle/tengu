plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
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

    // В KMP-модулях базовая задача detekt — NO-SOURCE (нет src/main):
    // анализ делают per-source-set задачи (detektCommonMainSourceSet, …) — вешаем их на check.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        tasks.named("check") {
            dependsOn(
                tasks.withType<dev.detekt.gradle.Detekt>().matching {
                    it.name.startsWith("detekt") && it.name.endsWith("SourceSet")
                },
            )
        }
    }

    // Нативные тесты исполняются только на своём хосте:
    // mingwX64Test на Linux требует Wine, linuxX64Test на Windows не запустится.
    val isWindowsHost = System.getProperty("os.name").lowercase().startsWith("windows")
    tasks.matching { it.name == "mingwX64Test" || it.name == "linuxX64Test" }.configureEach {
        onlyIf { (name == "mingwX64Test") == isWindowsHost }
    }
}
