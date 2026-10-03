package top.yukonga.mishka.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.theme.resolveIsDark

class WidgetThemeTest {
    @Test fun automaticThemeFollowsSystem() {
        assertFalse(ThemeConfig(colorMode = 0).resolveIsDark(false))
        assertTrue(ThemeConfig(colorMode = 0).resolveIsDark(true))
    }
    @Test fun explicitLightDoesNotFollowSystemDark() {
        assertFalse(ThemeConfig(colorMode = 1).resolveIsDark(true))
    }
    @Test fun explicitDarkDoesNotFollowSystemLight() {
        assertTrue(ThemeConfig(colorMode = 2).resolveIsDark(false))
    }
}
