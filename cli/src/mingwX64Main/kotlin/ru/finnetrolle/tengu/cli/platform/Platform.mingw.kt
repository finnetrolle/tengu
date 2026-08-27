@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.finnetrolle.tengu.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.winhttp.WinHttp
import platform.posix._O_BINARY
import platform.posix._setmode
import platform.windows.CreateDirectoryA
import platform.windows.SetConsoleOutputCP

internal actual val pathSeparator: String = "\\"

/** MSVCRT по умолчанию в text-режиме: CRLF-трансляция и Ctrl+Z-как-EOF ломают побайтовое чтение секретов. */
internal actual fun setStdinBinaryMode() {
    _setmode(0, _O_BINARY)
}

internal actual fun joinPath(vararg parts: String): String = parts.joinToString("\\")

/** Кодовая страница UTF-8 для SetConsoleOutputCP. */
private const val CP_UTF8 = 65001u

/** Системный WinHttp: TLS через SChannel, самодостаточный .exe (curl на mingw тянул бы libcurl.dll). */
internal actual fun httpEngine(): HttpClientEngineFactory<*> = WinHttp

/** Интерактивная консоль Windows по умолчанию не UTF-8; на пайпы агентов вызов не влияет. */
internal actual fun initConsoleUtf8() {
    SetConsoleOutputCP(CP_UTF8)
}

/**
 * mkdir -p покомпонентно (Win32 CreateDirectoryA). Ошибки не проверяем осознанно:
 * реальный сбой всплывёт fail-loud на первом же fopen-записи с понятным сообщением.
 */
internal actual fun createDirectories(path: String) {
    var prefix = ""
    for (part in path.split('\\', '/').filter { it.isNotEmpty() && it != "." }) {
        prefix = if (prefix.isEmpty()) part else "$prefix\\$part"
        CreateDirectoryA(prefix, null)
    }
}
