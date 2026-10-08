package app.syncwake.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.syncwake.ui.theme.SyncWakeColors
import app.syncwake.ui.theme.SyncWakeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Screens without a Scaffold (settings, editor, history, ring) render plain Text/Icon, which
     * use LocalContentColor. It must be the light foreground, not Compose's default black.
     */
    @Test
    fun defaultTextColourIsLightOnEveryScreen() {
        var contentColor = Color.Unspecified
        compose.setContent {
            SyncWakeTheme { contentColor = LocalContentColor.current }
        }
        compose.waitForIdle()
        assertEquals(SyncWakeColors.OnBackground, contentColor)
    }
}
