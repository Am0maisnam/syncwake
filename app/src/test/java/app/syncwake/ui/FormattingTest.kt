package app.syncwake.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.syncwake.Graph
import app.syncwake.settings.TimeFormat
import java.time.LocalTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FormattingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings get() = Graph.get(context).settings
    private val evening = LocalTime.of(19, 5)
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        settings.update { it.copy(timeFormat = TimeFormat.SYSTEM) }
        Locale.setDefault(originalLocale)
    }

    @Test
    fun twentyFourHourSetting() {
        settings.update { it.copy(timeFormat = TimeFormat.H24) }
        assertEquals("19:05", Formatting.time(context, evening))
        assertEquals("07:00", Formatting.time(context, LocalTime.of(7, 0)))
    }

    @Test
    fun twelveHourSetting() {
        settings.update { it.copy(timeFormat = TimeFormat.H12) }
        assertEquals("7:05 PM", Formatting.time(context, evening))
    }
}
