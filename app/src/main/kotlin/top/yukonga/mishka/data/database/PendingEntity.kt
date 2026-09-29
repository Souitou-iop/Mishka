package top.yukonga.mishka.data.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import top.yukonga.mishka.domain.model.ProfileType

@Entity(tableName = "pending")
data class PendingEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val type: ProfileType,
    val source: String,
    val userAgent: String = "",
    val ageSecretKey: String = "",
    // JSON 数组，避免为每个覆写选择单独建表；空串兼容旧数据库。
    val overrideIds: String = "",
    val overrideSortPreference: String = "",
    val interval: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val createdAt: Long,
)
