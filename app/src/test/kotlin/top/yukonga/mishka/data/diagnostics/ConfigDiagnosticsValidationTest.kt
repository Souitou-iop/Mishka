package top.yukonga.mishka.data.diagnostics

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yukonga.mishka.data.bridge.MishkaCoreError
import top.yukonga.mishka.domain.model.ConfigValidationResult
import java.io.File

class ConfigDiagnosticsValidationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `no transform still invokes validator with original workdir and age key`() {
        val workDir = temp.root
        var called = false
        val result = validateProfileConfig(workDir, null, "test-age-key") { actualWorkDir, transform, key ->
            called = true
            assertEquals(workDir, actualWorkDir)
            assertNull(transform)
            assertEquals("test-age-key", key)
        }
        assertTrue(called)
        assertSame(ConfigValidationResult.Valid, result)
    }

    @Test
    fun `missing or invalid raw config does not report valid without transforms`() {
        for (message in listOf("read config: missing config.yaml", "decrypt config: invalid key", "unmarshal config: invalid YAML")) {
            val result = validateProfileConfig(temp.root, null, "test-age-key") { _, transform, _ ->
                assertNull(transform)
                throw MishkaCoreError(message)
            }
            assertEquals(ConfigValidationResult.Invalid(message), result)
        }
    }

    @Test
    fun `transform validation failures preserve message and clean temporary manifest`() {
        val transform = temp.newFile("profile.transform.json")
        val result = validateProfileConfig(temp.root, transform, "") { _, actualTransform, _ ->
            assertEquals(transform, actualTransform)
            assertTrue(actualTransform!!.exists())
            throw MishkaCoreError("validate config: missing proxy group")
        }
        assertEquals(ConfigValidationResult.Invalid("missing proxy group"), result)
        assertFalse(transform.exists())
    }

    @Test
    fun `cancellation propagates and cleans temporary manifest`() {
        val transform = temp.newFile("profile.transform.json")
        val cancellation = CancellationException("cancelled")
        try {
            validateProfileConfig(temp.root, transform, "") { _: File, _: File?, _: String ->
                throw cancellation
            }
            throw AssertionError("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertFalse(transform.exists())
    }
}
