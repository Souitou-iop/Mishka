package top.yukonga.mishka.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class MishkaCliRequest(
    val command: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MishkaCliError(
    val code: String,
    val message: String,
)

@Serializable
data class MishkaCliResponse(
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: MishkaCliError? = null,
)

internal fun JsonObject.element(name: String): JsonElement? = this[name] ?: this[name.toSnakeCase()]

private fun String.toSnakeCase(): String = buildString(length + 4) {
    this@toSnakeCase.forEach { char ->
        if (char.isUpperCase()) append('_').append(char.lowercaseChar()) else append(char)
    }
}

internal fun JsonObject.string(name: String): String? =
    (element(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.requiredString(name: String): String =
    string(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing argument: $name")

internal fun JsonObject.boolean(name: String, default: Boolean = false): Boolean =
    (element(name) as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.int(name: String, default: Int = 0): Int =
    (element(name) as? JsonPrimitive)?.intOrNull ?: default

internal fun JsonObject.long(name: String, default: Long = 0L): Long =
    (element(name) as? JsonPrimitive)?.longOrNull ?: default
