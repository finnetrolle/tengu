package ru.finnetrolle.tengu.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Минимальный платформенный слой CLI: всё, что раньше делал java.*
 * (файлы, env, stdin, время см. kotlinx-datetime), собрано здесь.
 */
internal expect val pathSeparator: String

/** getenv: APPDATA/HOME/USERPROFILE/TENGU_BIN. */
internal expect fun envVar(name: String): String?

internal expect fun joinPath(vararg parts: String): String

internal expect fun fileExists(path: String): Boolean

/** null — файла нет или он не читается; содержимое — UTF-8. */
internal expect fun readTextFile(path: String): String?

/** UTF-8; при сбое записи бросает исключение наверх (fail-loud). */
internal expect fun writeTextFile(path: String, content: String)

/** mkdir -p: существующий каталог — не ошибка. */
internal expect fun createDirectories(path: String)

/** Все байты stdin до EOF (конвенция '-' у secret-флагов). */
internal expect fun readStdinAll(): String

/** Двоичный режим stdin перед побайтовым чтением: mingw — _setmode(0, _O_BINARY), linux — no-op. */
internal expect fun setStdinBinaryMode()

/** Системное время для TTL кэша (posix time()). */
internal expect fun nowEpochSeconds(): Long

/** WinHttp (Windows) | Curl (Linux) — системный/статический TLS без внешних зависимостей. */
internal expect fun httpEngine(): HttpClientEngineFactory<*>

/** Интерактивная консоль в UTF-8 (mingw: SetConsoleOutputCP); в пайп не влияет. */
internal expect fun initConsoleUtf8()
