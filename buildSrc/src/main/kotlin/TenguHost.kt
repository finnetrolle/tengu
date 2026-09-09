/** Единая точка знаний о хосте сборки: условные K/N-таргеты и гейты нативных тестов. */
object TenguHost {
    val isMac: Boolean = System.getProperty("os.name").lowercase().startsWith("mac")

    /** Имя нативного таргета, исполняемого на этом хосте (linuxX64 / macosArm64 / mingwX64). */
    val hostFamily: String = when {
        System.getProperty("os.name").lowercase().startsWith("windows") -> "mingwX64"
        isMac -> "macosArm64"
        else -> "linuxX64"
    }
}
