plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependency.check) apply false
    jacoco
}

val jacocoToolVersion = "0.8.15"

// K/N линкует Apple-таргеты только через полный Xcode (xcrun xcodebuild); CLT мало.
// lazy: xcrun исполняется при первом обращении из onlyIf (execution phase), а не на каждой конфигурации
val xcodeAvailable by lazy {
    runCatching {
        providers.exec { commandLine("xcrun", "xcodebuild", "-version") }.result.get().exitValue == 0
    }.getOrDefault(false)
}
val jacocoTestTasks = mapOf(
    ":protocol" to "jvmTest",
    ":toon" to "jvmTest",
    ":toolkit" to "test",
    ":plugins:jira" to "test",
    ":server" to "test",
)

jacoco {
    toolVersion = jacocoToolVersion
}

allprojects {
    group = "ru.finnetrolle.tengu"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "org.owasp.dependencycheck")

    configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
        failBuildOnCVSS.set(7.0f)
        failOnError.set(true)
        formats.set(listOf("HTML", "JSON"))
        analyzedTypes.set(analyzedTypes.get() + "klib")
        analyzers.zipExtensions.set("klib")
        val nvdApiKey = providers.environmentVariable("NVD_API_KEY").orNull?.takeIf { it.isNotBlank() }
        if (nvdApiKey == null) {
            // Dependency-Check 13.0.0 sends an invalid empty API key; use NVD's official feed.
            nvd.datafeedUrl.set("https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz")
        } else {
            nvd.apiKey.set(nvdApiKey)
        }
    }

    tasks.withType<org.owasp.dependencycheck.gradle.tasks.Analyze>().configureEach {
        // Vulnerability data can change even when the dependency graph has not.
        outputs.upToDateWhen { false }
    }

    if (path in jacocoTestTasks) {
        apply(plugin = "jacoco")
        configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
            toolVersion = jacocoToolVersion
        }
    }

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
    // mingwX64Test на Linux/macOS требует Wine, linuxX64Test и macosArm64Test — чужой ОС.
    val hostFamily = TenguHost.hostFamily
    tasks.matching { it.name == "mingwX64Test" || it.name == "linuxX64Test" || it.name == "macosArm64Test" }.configureEach {
        onlyIf { name.removeSuffix("Test") == hostFamily && (name != "macosArm64Test" || xcodeAvailable) }
    }
    tasks.matching { it.name.startsWith("link") && it.name.contains("MacosArm64") }.configureEach {
        onlyIf { xcodeAvailable }
    }
}

tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Runs JVM tests and generates aggregate JaCoCo XML and HTML reports."

    dependsOn(jacocoTestTasks.map { (projectPath, taskName) -> "$projectPath:$taskName" })

    executionData.from(
        jacocoTestTasks.map { (projectPath, taskName) ->
            project(projectPath).layout.buildDirectory.file("jacoco/$taskName.exec")
        },
    )
    classDirectories.from(
        jacocoTestTasks.keys.flatMap { projectPath ->
            val buildDir = project(projectPath).layout.buildDirectory
            listOf(
                buildDir.dir("classes/kotlin/main"),
                buildDir.dir("classes/kotlin/jvm/main"),
                buildDir.dir("classes/java/main"),
            )
        },
    )
    sourceDirectories.from(
        jacocoTestTasks.keys.flatMap { projectPath ->
            val projectDir = project(projectPath).layout.projectDirectory
            listOf(
                projectDir.dir("src/main/kotlin"),
                projectDir.dir("src/main/java"),
                projectDir.dir("src/commonMain/kotlin"),
                projectDir.dir("src/commonMain/java"),
                projectDir.dir("src/jvmMain/kotlin"),
                projectDir.dir("src/jvmMain/java"),
            )
        },
    )

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/test/html"))
        csv.required.set(false)
    }
}
