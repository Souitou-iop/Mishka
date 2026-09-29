package top.yukonga.mishka.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

@Serializable
data class Subscription(
    val id: String = "",
    val name: String = "",
    val type: ProfileType = ProfileType.Url,
    val url: String = "",
    val userAgent: String = "",
    val ageSecretKey: String = "",
    val overrideIds: ImmutableList<String> = persistentListOf(),
    val overrideSortPreference: ImmutableList<String> = persistentListOf(),
    val interval: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updatedAt: Long = 0,
    val isActive: Boolean = false,
    val imported: Boolean = false,
    val pending: Boolean = false,
)


/** 覆写选择页按用户排序；被选中的条目按该顺序执行。 */
val Subscription.orderedOverrideIds: List<String>
    get() {
        val selected = overrideIds.toSet()
        val ordered = overrideSortPreference.filter { it in selected }.distinct().toMutableList()
        overrideIds.forEach { if (it !in ordered) ordered += it }
        return ordered
    }
