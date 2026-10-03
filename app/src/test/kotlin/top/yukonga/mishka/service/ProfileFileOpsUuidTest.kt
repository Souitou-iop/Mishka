package top.yukonga.mishka.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFileOpsUuidTest {

    @Test
    fun `accepts canonical version four uuid`() {
        assertTrue(ProfileFileOps.isValidProfileUuid("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `rejects traversal and non uuid names`() {
        assertFalse(ProfileFileOps.isValidProfileUuid("../../outside"))
        assertFalse(ProfileFileOps.isValidProfileUuid("not-a-uuid"))
    }

    @Test
    fun `rejects non version four uuid`() {
        assertFalse(ProfileFileOps.isValidProfileUuid("550e8400-e29b-11d4-a716-446655440000"))
    }
}
