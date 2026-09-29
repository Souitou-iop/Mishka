package top.yukonga.mishka.domain.repository

import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile

interface OverrideProfileRepository {
    val profiles: StateFlow<ImmutableList<OverrideProfile>>
    suspend fun isUsedByCurrent(id: String): Boolean
    suspend fun readContent(id: String): String
    suspend fun addRemote(name: String, url: String, format: OverrideFormat): OverrideProfile
    suspend fun addLocal(name: String, fileName: String, format: OverrideFormat, content: String): OverrideProfile
    suspend fun addBlank(name: String, format: OverrideFormat): OverrideProfile
    suspend fun edit(id: String, name: String, url: String, format: OverrideFormat)
    suspend fun saveContent(id: String, content: String)
    suspend fun update(id: String)
    suspend fun delete(id: String)
    suspend fun setSelection(subscriptionId: String, overrideIds: List<String>, sortPreference: List<String>)
}
