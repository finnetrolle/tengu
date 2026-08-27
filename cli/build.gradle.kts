plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // application-плагина нет: CLI — native-only, дев-режим и e2e работают нативным бинарём
}

// ktor-client-curl 3.4+ бандлит libcurl/libssl/libcrypto статикой внутри klib,
// но передаёт их линкеру в порядке, при котором libssl.a ссылается на символы
// уже пройденного libcrypto.a (KTOR-9460). Обход: распаковываем бандл и
// добавляем архивы в конец командной строки линкера заново.
val curlStaticDir = layout.buildDirectory.dir("curlStatic")

val curlKlib = configurations.create("curlKlib")
dependencies {
    curlKlib("io.ktor:ktor-client-curl-linuxx64:${libs.versions.ktor.get()}")
}

// Распаковка статических либ из ktor-curl klib во flat-каталог для linkerOpts
abstract class ExtractCurlStaticLibs : DefaultTask() {
    @get:InputFiles
    abstract val klib: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun extract() {
        project.copy {
            klib.files.filter { it.name.endsWith(".klib") }.forEach { klibFile ->
                from(project.zipTree(klibFile)) // .a бандлит только Cinterop-klib, остальные дают пусто
            }
            include("**/included/*.a")
            into(outDir)
            eachFile { path = name } // flatten: libcurl.a, libssl.a, libcrypto.a, libnghttp2.a
            includeEmptyDirs = false
        }
    }
}

val extractCurlStatic = tasks.register<ExtractCurlStaticLibs>("extractCurlStatic") {
    group = "build"
    description = "Extracts static curl/ssl/crypto libs from the ktor-curl klib for the Linux CLI link (KTOR-9460 workaround)"
    klib.from(curlKlib)
    outDir.set(curlStaticDir)
}

kotlin {
    mingwX64 {
        binaries.executable {
            entryPoint = "ru.finnetrolle.tengu.cli.main"
            baseName = "tengu" // → tengu.exe
        }
    }
    linuxX64 {
        binaries.executable {
            entryPoint = "ru.finnetrolle.tengu.cli.main"
            baseName = "tengu" // → tengu.kexe
            // обход KTOR-9460: бандл статических либ в конец линии линкера (см. extractCurlStatic)
            linkerOpts(
                "-L${curlStaticDir.get().asFile.invariantSeparatorsPath}",
                "-l:libnghttp2.a",
                "-l:libssl.a",
                "-l:libcrypto.a",
            )
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":protocol"))
                implementation(project(":toon"))
                implementation(libs.clikt)
                implementation(libs.ktor.client.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        mingwX64Main {
            dependencies {
                // системный WinHttp: TLS через SChannel, .exe без внешних DLL
                implementation(libs.ktor.client.winhttp)
            }
        }
        linuxX64Main {
            dependencies {
                // libcurl линкуется статически → самодостаточный tengu.kexe
                implementation(libs.ktor.client.curl)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.contains("LinuxX64")) dependsOn(extractCurlStatic)
}
