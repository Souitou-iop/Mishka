package top.yukonga.mishka.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSnapshotJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `auth key survives json round trip`() {
        val snapshot = BackupSnapshot(version = 1, createdAt = 42L, tailscaleAuthKey = "tskey-auth-x")

        val decoded = json.decodeFromString<BackupSnapshot>(json.encodeToString(snapshot))

        assertEquals("tskey-auth-x", decoded.tailscaleAuthKey)
    }

    @Test
    fun `legacy snapshot without auth key field decodes empty`() {
        // 旧版本产出的 backup.json 没有该字段，新版本恢复时按未设置处理
        val legacy = """{"version":1,"createdAt":0}"""

        val decoded = json.decodeFromString<BackupSnapshot>(legacy)

        assertEquals("", decoded.tailscaleAuthKey)
    }
}
