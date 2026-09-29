package top.yukonga.mishka.data.database

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json

typealias OverrideIds = ImmutableList<String>

private val overrideJson = Json { ignoreUnknownKeys = true }

internal fun String.decodeOverrideIds(): OverrideIds =
    if (isBlank()) {
        emptyList<String>().toPersistentList()
    } else {
        runCatching { overrideJson.decodeFromString<List<String>>(this).toPersistentList() }
            .getOrDefault(emptyList<String>().toPersistentList())
    }

internal fun List<String>.encodeOverrideIds(): String = overrideJson.encodeToString(this)
