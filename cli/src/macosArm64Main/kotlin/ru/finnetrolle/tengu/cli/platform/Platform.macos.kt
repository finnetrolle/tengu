@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.finnetrolle.tengu.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import platform.posix.mkdir

/** Darwin (NSURLSession): системный фреймворк macOS, TLS через Security.framework — без внешних зависимостей. */
internal actual fun httpEngine(): HttpClientEngineFactory<*> = Darwin

/** Права создаваемых каталогов: rwxr-xr-x (0755 = 0x1ED); umask может только сузить. На darwin mode_t — UShort. */
private const val DIR_MODE: UShort = 0x1EDu

internal actual fun mkdirPosix(dir: String): Int = mkdir(dir, DIR_MODE)
