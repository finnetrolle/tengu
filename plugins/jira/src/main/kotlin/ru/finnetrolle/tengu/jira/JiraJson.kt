package ru.finnetrolle.tengu.jira

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------- JSON-хелперы ----------

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

internal fun JsonObject.nested(child: String, field: String): String? =
    (this[child] as? JsonObject)?.str(field)
