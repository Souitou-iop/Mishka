package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class OverrideProfile(
    val id: String,
    val name: String,
    val sourceType: OverrideSourceType,
    val format: OverrideFormat,
    val sourceLocation: String,
    val createdAt: Long,
    val lastUpdatedAt: Long? = null,
) {
    val isRemote: Boolean get() = sourceType == OverrideSourceType.Remote
    val fileName: String get() = "$id.${format.extension}"
}

@Serializable
enum class OverrideSourceType { Local, Remote }

@Serializable
enum class OverrideFormat(val extension: String) {
    Yaml("yaml"),
    JavaScript("js"),
}
