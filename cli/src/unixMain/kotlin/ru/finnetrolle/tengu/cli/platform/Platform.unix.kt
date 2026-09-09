package ru.finnetrolle.tengu.cli.platform

/** Общий POSIX-слой linux и macOS: пути, UTF-8-консоль, mkdir -p. */

internal actual val pathSeparator: String = "/"

/** POSIX stdin изначально байтовый. */
internal actual fun setStdinBinaryMode() {
    /* no-op: нужен как actual к expect-декларации */
}

internal actual fun joinPath(vararg parts: String): String = parts.joinToString("/")

/** На POSIX-терминалах консоль уже UTF-8. */
internal actual fun initConsoleUtf8() {
    /* no-op: нужен как actual к expect-декларации */
}

/** mkdir с правами 0755: тип mode_t платформенно-зависим (UInt на linux, UShort на darwin), потому actual. */
internal expect fun mkdirPosix(dir: String): Int

/**
 * mkdir -p покомпонентно. Ошибки mkdir не проверяем осознанно: реальный сбой
 * всплывёт fail-loud на первом же fopen-записи с понятным сообщением.
 */
internal actual fun createDirectories(path: String) {
    var prefix = if (path.startsWith("/")) "/" else ""
    for (part in path.split('/').filter { it.isNotEmpty() && it != "." }) {
        prefix = when {
            prefix == "/" -> "/$part"
            prefix.isEmpty() -> part
            else -> "$prefix/$part"
        }
        mkdirPosix(prefix)
    }
}
