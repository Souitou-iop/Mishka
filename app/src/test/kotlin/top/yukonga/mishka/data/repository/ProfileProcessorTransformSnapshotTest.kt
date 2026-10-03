package top.yukonga.mishka.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.orderedOverrideIds

class ProfileProcessorTransformSnapshotTest {
    private fun snapshot() = PendingSnapshot(
        uuid = "profile-id",
        name = "Profile",
        type = ProfileType.Url,
        source = "https://example.invalid/config.yaml",
        userAgent = "TestAgent",
        ageSecretKey = "test-age-key",
        interval = 900_000L,
        overrideIds = "[\"yaml\",\"js\",\"last\"]",
        overrideSortPreference = "[\"js\",\"unused\",\"yaml\"]",
    )

    @Test
    fun `transform subscription preserves selected order and age key`() {
        val subscription = snapshot().toSubscription()
        assertEquals("profile-id", subscription.id)
        assertEquals("TestAgent", subscription.userAgent)
        assertEquals("test-age-key", subscription.ageSecretKey)
        assertEquals(listOf("js", "yaml", "last"), subscription.orderedOverrideIds)
    }

    @Test
    fun `legacy empty selections remain empty`() {
        val subscription = snapshot().copy(overrideIds = "", overrideSortPreference = "").toSubscription()
        assertTrue(subscription.orderedOverrideIds.isEmpty())
        assertEquals("test-age-key", subscription.ageSecretKey)
    }

    @Test
    fun `commit rejects changed age key selection or ordering`() {
        val snapshot = snapshot()
        assertFalse(snapshot.hasSameTransformInputs("new-key", snapshot.overrideIds, snapshot.overrideSortPreference))
        assertFalse(snapshot.hasSameTransformInputs(snapshot.ageSecretKey, "[\"other\"]", snapshot.overrideSortPreference))
        assertFalse(snapshot.hasSameTransformInputs(snapshot.ageSecretKey, snapshot.overrideIds, "[\"yaml\",\"js\"]"))
    }

    @Test
    fun `equivalent selection encoding and unrelated name edits are accepted`() {
        val snapshot = snapshot().copy(name = "Renamed")
        assertTrue(
            snapshot.hasSameTransformInputs(
                snapshot.ageSecretKey,
                "[ \"yaml\", \"js\", \"last\" ]",
                snapshot.overrideSortPreference,
            ),
        )
    }
}
