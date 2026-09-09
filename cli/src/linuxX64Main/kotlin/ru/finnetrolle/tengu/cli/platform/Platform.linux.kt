@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.finnetrolle.tengu.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl
import platform.posix.mkdir

/** Curl: libcurl линкуется статически тулчейном — самодостаточный tengu.kexe. */
internal actual fun httpEngine(): HttpClientEngineFactory<*> = Curl

/** Права создаваемых каталогов: rwxr-xr-x (0755 = 0x1ED); umask может только сузить. На linux mode_t — UInt. */
private const val DIR_MODE = 0x1EDu

internal actual fun mkdirPosix(dir: String): Int = mkdir(dir, DIR_MODE)
