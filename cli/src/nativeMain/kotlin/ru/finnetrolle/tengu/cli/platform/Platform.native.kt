@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.finnetrolle.tengu.cli.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.stdin
import platform.posix.time

private const val BUF = 65536

/** Общий posix-слой mingw/linux: env, файлы, stdin. */
internal actual fun envVar(name: String): String? = getenv(name)?.toKString()

internal actual fun fileExists(path: String): Boolean =
    fopen(path, "rb")?.let { fclose(it); true } ?: false

internal actual fun readTextFile(path: String): String? = memScoped {
    val f = fopen(path, "rb") ?: return@memScoped null
    try {
        // читаем до EOF одним ByteArray: многобайтовый UTF-8 не должен резаться по чанкам
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val buf = allocArray<ByteVar>(BUF)
        while (true) {
            val n = fread(buf, 1UL, BUF.toULong(), f).toInt()
            if (n <= 0) break
            chunks += buf.readBytes(n)
            total += n
        }
        concat(chunks, total).decodeToString()
    } finally {
        fclose(f)
    }
}

internal actual fun writeTextFile(path: String, content: String) = memScoped {
    val f = fopen(path, "wb") ?: error("cannot open $path for writing")
    try {
        val bytes = content.encodeToByteArray()
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1UL, bytes.size.toULong(), f)
                if (written != bytes.size.toULong()) error("short write to $path")
            }
        }
    } finally {
        fclose(f)
    }
}

/** Все байты stdin до EOF; сперва двоичный режим (mingw-актуал ставит _setmode), затем fread-цикл. */
internal actual fun readStdinAll(): String = memScoped {
    setStdinBinaryMode()
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    val buf = allocArray<ByteVar>(BUF)
    while (true) {
        val n = fread(buf, 1UL, BUF.toULong(), stdin).toInt()
        if (n <= 0) break
        chunks += buf.readBytes(n)
        total += n
    }
    concat(chunks, total).decodeToString()
}

private fun concat(chunks: List<ByteArray>, total: Int): ByteArray {
    val out = ByteArray(total)
    var off = 0
    for (c in chunks) {
        c.copyInto(out, off)
        off += c.size
    }
    return out
}

internal actual fun nowEpochSeconds(): Long = time(null)
