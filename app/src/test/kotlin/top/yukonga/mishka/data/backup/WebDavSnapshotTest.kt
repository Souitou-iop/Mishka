package top.yukonga.mishka.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavSnapshotTest {
    @Test
    fun `snapshot filename carries numeric version`() {
        val name = WebDavClient.newSnapshotName(12)

        assertEquals(12L, WebDavClient.parseSnapshotVersion(name))
        assertNotNull(name.substringAfterLast(".", ""))
    }

    @Test
    fun `legacy fixed filename is not a snapshot`() {
        assertNull(WebDavClient.parseSnapshotVersion(WebDavClient.LEGACY_FILE))
    }

    @Test
    fun `snapshot parser rejects arbitrary remote names`() {
        assertNull(WebDavClient.parseSnapshotVersion("backup-12.zip"))
        assertNull(WebDavClient.parseSnapshotVersion("mishka-12.android.zip"))
    }
}
