package top.yukonga.mishka.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreResultTest {
    @Test
    fun `missing key in legacy backup is not a partial restore`() {
        assertFalse(BackupRestoreResult(false, false).requiresTailscaleReauth)
    }

    @Test
    fun `failed key restore is reported as reauthentication required`() {
        assertTrue(BackupRestoreResult(true, false).requiresTailscaleReauth)
    }

    @Test
    fun `successful key restore is complete`() {
        assertFalse(BackupRestoreResult(true, true).requiresTailscaleReauth)
    }
}
