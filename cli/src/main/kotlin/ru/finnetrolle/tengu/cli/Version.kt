package ru.finnetrolle.tengu.cli

/**
 * Leaf-модуль без единого импорта: fast-path `--version` отвечает до загрузки
 * Clikt/Ktor (AXI §10 — латентность зондирующего вызова это эргономика).
 */
object Version {
    const val VERSION = "0.1.0"
}
