@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.finnetrolle.tengu.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl
import platform.posix.mkdir

internal actual val pathSeparator: String = "/"

/** POSIX stdin изначально байтовый. */
internal actual fun setStdinBinaryMode() {
    /* no-op: нужен как actual к expect-декларации */
}

internal actual fun joinPath(vararg parts: String): String = parts.joinToString("/")

/** Curl: libcurl линкуется статически тулчейном — самодостаточный tengu.kexe. */
internal actual fun httpEngine(): HttpClientEngineFactory<*> = Curl

/** На Linux консоль уже UTF-8. */
internal actual fun initConsoleUtf8() {
    /* no-op: нужен как actual к expect-декларации */
}

/** Права создаваемых каталогов: rwxr-xr-x (0755 = 0x1ED); umask может только сузить. */
private const val DIR_MODE = 0x1EDu

/**
 * mkdir -p покомпонентно. Ошибки mkdir не проверяем осознанно: реальный сбой
 * всплывёт fail-loud на первом же fopen-записи с понятным сообщением.
 */
internal actual fun createDirectories(path: String) {
    var prefix = ""
    for (part in path.split('/').filter { it.isNotEmpty() && it != "." }) {
        prefix = if (prefix.isEmpty()) part else "$prefix/$part"
        mkdir(prefix, DIR_MODE)
    }
}
