package bukin.magnifier

import androidx.annotation.StringRes
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The colour modes, for reading with low vision. Each is a 4x5 colour matrix in Android's
 * [android.graphics.ColorMatrix] layout (rows R, G, B, A; columns R, G, B, A and an offset in
 * 0..255), used three ways: on the live picture (the preview's hardware layer), on the frozen
 * picture, and on the pictures that are saved or shared. The controls are never filtered.
 */
enum class ColorMode(
    /** The mode's name, spoken as the colour button's state and shown when it changes. */
    @param:StringRes val label: Int,
    /** The name on the colour button. */
    @param:StringRes val shortLabel: Int,
    /**
     * True when more light on the page makes the screen darker (the inverting modes). The
     * brightness slider then works the other way round on the camera, so "lighter" always makes
     * the screen lighter (see [lightFor]).
     */
    val invertsLight: Boolean,
) {
    /** The camera's colours. */
    NORMAL(R.string.colour_normal, R.string.colour_short_normal, invertsLight = false),

    /** Grey, with the contrast doubled: dark text on white paper becomes black on white. */
    HIGH_CONTRAST(R.string.colour_high_contrast, R.string.colour_short_high_contrast, invertsLight = false),

    /** A colour negative: white paper turns black, black text white. */
    INVERTED(R.string.colour_inverted, R.string.colour_short_inverted, invertsLight = true),

    /** Yellow text on black: inverted grey with the contrast doubled, drawn in yellow. */
    YELLOW_ON_BLACK(R.string.colour_yellow_on_black, R.string.colour_short_yellow_on_black, invertsLight = true);

    /**
     * The light on the page (camera exposure live, [Brightness.gain] level frozen) for a
     * brightness slider position, which always means the screen: + is a lighter screen in every
     * mode. The same sign flip takes a camera value back to a slider position.
     */
    fun lightFor(screenLevel: Int): Int = if (invertsLight) -screenLevel else screenLevel

    /** The mode after this one, for the colour button: Normal follows the last. */
    fun next(): ColorMode = entries[(ordinal + 1) % entries.size]

    /**
     * The colour matrix for this mode, after a brightness [gain] on the camera's colours (1 =
     * unchanged; see [Brightness]). The gain comes first, like more or less light on the page, as
     * the camera's exposure does live; [lightFor] picks the gain that makes the screen lighter.
     */
    fun matrix(gain: Float = 1f): FloatArray {
        val g = gain
        return when (this) {
            NORMAL -> floatArrayOf(
                g, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, g, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            HIGH_CONTRAST -> {
                // Grey = luminance; then the contrast around the middle grey: k * (grey - 128) + 128.
                val k = CONTRAST
                val r = k * g * LUMA_R
                val gg = k * g * LUMA_G
                val b = k * g * LUMA_B
                val offset = MID * (1f - k)
                floatArrayOf(
                    r, gg, b, 0f, offset,
                    r, gg, b, 0f, offset,
                    r, gg, b, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            }
            INVERTED -> floatArrayOf(
                -g, 0f, 0f, 0f, 255f,
                0f, -g, 0f, 0f, 255f,
                0f, 0f, -g, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
            YELLOW_ON_BLACK -> {
                // Inverted grey (255 - grey) with the contrast doubled, in red and green; no blue.
                val k = CONTRAST
                val r = -k * g * LUMA_R
                val gg = -k * g * LUMA_G
                val b = -k * g * LUMA_B
                // k * ((255 - grey) - 128) + 128 = -k * grey + k * (255 - 128) + 128.
                val offset = k * (255f - MID) + MID
                floatArrayOf(
                    r, gg, b, 0f, offset,
                    r, gg, b, 0f, offset,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            }
        }
    }

    companion object {
        /** Luminance weights (Rec. 709 / sRGB). */
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f

        /** How much High contrast and Yellow on black stretch the greys. */
        const val CONTRAST = 2f
        private const val MID = 128f

        /** The saved mode, or Normal for a missing or unknown name. */
        fun fromName(name: String?): ColorMode = entries.firstOrNull { it.name == name } ?: NORMAL

        /**
         * [matrix] applied to one opaque ARGB pixel, clamped to 0..255 like Android's colour
         * filter. For tests and for checking saved pictures.
         */
        fun apply(matrix: FloatArray, argb: Int): Int {
            val a = (argb ushr 24) and 0xFF
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            fun row(i: Int): Int {
                val o = i * 5
                val v = matrix[o] * r + matrix[o + 1] * g + matrix[o + 2] * b + matrix[o + 3] * a + matrix[o + 4]
                return v.roundToInt().coerceIn(0, 255)
            }
            return (row(3) shl 24) or (row(0) shl 16) or (row(1) shl 8) or row(2)
        }
    }
}

/**
 * The brightness slider: + always makes the screen lighter. Live, it moves the camera's exposure
 * compensation (see [Exposure]); frozen, it is a [gain] in the colour matrix. In the inverting
 * colour modes both go the other way ([ColorMode.lightFor]): less light on the page is a lighter
 * screen there.
 */
object Brightness {
    /** The frozen picture's slider: -10..10, 0 = as the photo was taken. */
    const val FROZEN_LEVELS = 10

    /** At either end of the frozen slider, the picture gets this many stops darker or lighter. */
    const val FROZEN_MAX_STOPS = 1.5f

    /** The colour-matrix gain for a frozen light [level]: each level is 0.15 of a stop. */
    fun gain(level: Int): Float {
        val clamped = level.coerceIn(-FROZEN_LEVELS, FROZEN_LEVELS)
        return 2f.pow(clamped * FROZEN_MAX_STOPS / FROZEN_LEVELS)
    }

    /**
     * The live slider over the camera's exposure-compensation indices [minIndex]..[maxIndex].
     * The slider moves in [step]s of indices, about 20 to the whole range (the step TalkBack and
     * the darker / lighter buttons take), on a grid through 0 so the middle is always "as the
     * camera sees it". [from]..[to] is the grid's part of the camera's range.
     */
    data class Exposure(val from: Int, val to: Int, val step: Int) {
        /** The index for a slider value, on the grid. */
        fun indexFor(value: Float): Int = ((value / step).roundToInt() * step).coerceIn(from, to)

        /** The slider value for the camera's index, snapped to the grid. */
        fun valueFor(index: Int): Float = indexFor(index.toFloat()).toFloat()

        /** The index [steps] steps from [index], within the range. */
        fun stepFrom(index: Int, steps: Int): Int = (indexFor(index.toFloat()) + steps * step).coerceIn(from, to)

        /** The slider position in steps from the middle, as spoken: "+3". */
        fun level(index: Int): Int = indexFor(index.toFloat()) / step

        /** The camera's index for a slider position (a screen brightness) in [mode]. */
        fun cameraIndex(sliderValue: Float, mode: ColorMode): Int =
            indexFor(mode.lightFor(indexFor(sliderValue)).toFloat())

        /** The slider position (a screen brightness) for the camera's [index] in [mode]. */
        fun sliderValue(index: Int, mode: ColorMode): Float = valueFor(mode.lightFor(index))

        companion object {
            /**
             * The slider for a camera with this range, or null when it has none: a camera that
             * doesn't support exposure compensation reports 0..0.
             */
            fun of(minIndex: Int, maxIndex: Int): Exposure? {
                if (minIndex >= 0 || maxIndex <= 0) return null
                val step = maxOf(1, ((maxIndex - minIndex) / 20f).roundToInt())
                val from = -(-minIndex / step) * step
                val to = (maxIndex / step) * step
                if (from >= 0 || to <= 0) return null
                return Exposure(from, to, step)
            }
        }
    }
}
