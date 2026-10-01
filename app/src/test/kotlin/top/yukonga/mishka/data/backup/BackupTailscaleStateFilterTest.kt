package top.yukonga.mishka.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTailscaleStateFilterTest {
    @Test
    fun `tailscale state directory entries are excluded`() {
        assertTrue(BackupManager.isTailscaleStateEntry("uuid/tailscale/tsnet_state"))
        assertTrue(BackupManager.isTailscaleStateEntry("imported/uuid/tailscale/tsnet_state"))
    }

    @Test
    fun `regular profile files still pass the filter`() {
        assertFalse(BackupManager.isTailscaleStateEntry("uuid/config.yaml"))
        assertFalse(BackupManager.isTailscaleStateEntry("uuid/providers/foo.yaml"))
        // 段匹配而非前缀：生成的 transform 脚本必须留在备份里
        assertFalse(BackupManager.isTailscaleStateEntry("uuid/tailscale.generated.js"))
    }
}
