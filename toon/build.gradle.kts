plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // jvm — для :server-инфраструктуры; native — для :cli
    jvm()
    mingwX64()
    linuxX64()
    // macOS-таргет только на mac-хосте: K/N не кросс-компилирует Apple-таргеты с Linux/Windows
    if (TenguHost.isMac) macosArm64()
    jvmToolchain(21)

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test) // jvmTest наследует через иерархию source set'ов
            }
        }
    }
}
