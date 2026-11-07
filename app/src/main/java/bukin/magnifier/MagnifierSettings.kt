package bukin.magnifier

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What the app remembers between uses: the zoom, the light and the colour mode. Kept in
 * [FILE] (`magnifier_settings.xml`), the only file in the app's backup (res/xml/backup_rules.xml).
 *
 * Deliberately not remembered: the brightness (every look starts as the camera sees it) and the
 * focus (always Auto at start).
 */
class MagnifierSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    /** CameraX's linear zoom, 0 (widest) to 1 (closest). */
    var zoomLinear: Float
        get() = prefs.getFloat(KEY_ZOOM, 0f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        set(value) = prefs.edit { putFloat(KEY_ZOOM, value.coerceIn(0f, 1f)) }

    /** Whether the user last turned the light on. It comes back on when the camera opens. */
    var lightOn: Boolean
        get() = prefs.getBoolean(KEY_LIGHT, false)
        set(value) = prefs.edit { putBoolean(KEY_LIGHT, value) }

    var colourMode: ColorMode
        get() = ColorMode.fromName(prefs.getString(KEY_COLOUR, null))
        set(value) = prefs.edit { putString(KEY_COLOUR, value.name) }

    companion object {
        /** The file name without ".xml"; backup_rules.xml and data_extraction_rules.xml name it. */
        const val FILE = "magnifier_settings"
        const val KEY_ZOOM = "zoom_linear"
        const val KEY_LIGHT = "light_on"
        const val KEY_COLOUR = "colour_mode"
    }
}
