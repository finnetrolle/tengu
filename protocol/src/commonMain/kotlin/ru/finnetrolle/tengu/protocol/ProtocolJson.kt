package ru.finnetrolle.tengu.protocol

import kotlinx.serialization.json.Json

/** Единая конфигурация JSON для провода: компактно (без дефолтов), терпимо к новым полям. */
object ProtocolJson {
    val json: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
}
