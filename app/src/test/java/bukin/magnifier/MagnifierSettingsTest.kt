package bukin.magnifier

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The remembered settings: zoom, light and colour mode, and nothing else. */
@RunWith(AndroidJUnit4::class)
class MagnifierSettingsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val file get() = app.getSharedPreferences(MagnifierSettings.FILE, Context.MODE_PRIVATE)

    @Test
    fun aFreshInstallStartsWideWithTheLightOffInNormalColours() {
        val settings = MagnifierSettings(app)
        assertEquals(0f, settings.zoomLinear, 0f)
        assertFalse(settings.lightOn)
        assertEquals(ColorMode.NORMAL, settings.colourMode)
    }

    @Test
    fun whatIsSavedComesBackInANewInstance() {
        MagnifierSettings(app).apply {
            zoomLinear = 0.45f
            lightOn = true
            colourMode = ColorMode.YELLOW_ON_BLACK
        }
        val again = MagnifierSettings(app)
        assertEquals(0.45f, again.zoomLinear, 0f)
        assertTrue(again.lightOn)
        assertEquals(ColorMode.YELLOW_ON_BLACK, again.colourMode)
    }

    @Test
    fun badStoredValuesFallBackToTheDefaults() {
        file.edit().putFloat(MagnifierSettings.KEY_ZOOM, Float.NaN).putString(MagnifierSettings.KEY_COLOUR, "SEPIA").commit()
        val settings = MagnifierSettings(app)
        assertEquals(0f, settings.zoomLinear, 0f)
        assertEquals(ColorMode.NORMAL, settings.colourMode)
        file.edit().putFloat(MagnifierSettings.KEY_ZOOM, 7f).commit()
        assertEquals(1f, settings.zoomLinear, 0f)
        settings.zoomLinear = -3f
        assertEquals(0f, file.getFloat(MagnifierSettings.KEY_ZOOM, 99f), 0f)
    }

    @Test
    fun onlyTheThreeSettingsAreStored() {
        MagnifierSettings(app).apply {
            zoomLinear = 0.2f
            lightOn = false
            colourMode = ColorMode.INVERTED
        }
        // No brightness, no focus.
        assertEquals(
            setOf(MagnifierSettings.KEY_ZOOM, MagnifierSettings.KEY_LIGHT, MagnifierSettings.KEY_COLOUR),
            file.all.keys,
        )
    }
}
