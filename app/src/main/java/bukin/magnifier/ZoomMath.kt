package bukin.magnifier

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Zoom maths shared by the slider, the volume keys, the zoom buttons and pinch.
 *
 * The slider and the steps work on CameraX's *linear* zoom (0 = widest, 1 = closest), which
 * shrinks the visible width by the same amount for every equal move, so the slider feels even
 * from end to end. Pinch works on the zoom *ratio*, so the picture follows the fingers.
 */
object ZoomMath {
    /** The slider runs 0..100 (a percentage of linear zoom), not 0..1: the Material slider's
     * accessibility actions move by 1/20 of a continuous range, rounded to whole units. */
    const val SLIDER_MAX = 100f

    /** One volume-key press or zoom-button tap: 1/20 of the range, the same step TalkBack's
     * swipe makes on the slider. */
    const val STEP = 0.05f

    // A zoom less than a quarter step below a grid point counts as on it, so every step moves at
    // least a quarter step (and at most one and a quarter). The camera echoes a zoom a little off
    // the grid (the A20 reports 1.5975x for 1.6x, linear 0.4995 for 0.5), and a turn of the lens
    // ring stops anywhere: without this, the next tap or key press could move 0.0005, which shows
    // nothing.
    private const val GRID_EPSILON = 0.25f

    fun sliderToLinear(value: Float): Float = (value / SLIDER_MAX).coerceIn(0f, 1f)

    fun linearToSlider(linear: Float): Float = linear.coerceIn(0f, 1f) * SLIDER_MAX

    /**
     * The linear zoom [steps] steps away from [linear] (negative = zoom out), on the grid of
     * [STEP]s, clamped to 0..1. Starting between two grid points, the first step goes to the
     * next grid point in that direction, or the one after if the next is less than a quarter
     * step away.
     */
    fun stepLinear(linear: Float, steps: Int): Float {
        val current = linear.coerceIn(0f, 1f)
        if (steps == 0) return current
        val index = current / STEP
        val target = if (steps > 0) {
            floor(index + GRID_EPSILON) + steps
        } else {
            ceil(index - GRID_EPSILON) + steps
        }
        return (target * STEP).coerceIn(0f, 1f)
    }

    /** Whether the camera can zoom at all. */
    fun canZoom(minRatio: Float, maxRatio: Float): Boolean = maxRatio > minRatio

    /**
     * The zoom ratio CameraX uses for [linear], between [minRatio] and [maxRatio]. CameraX moves
     * the crop width (1 / ratio) evenly with linear zoom; this is the same formula, used to word
     * the slider's value for TalkBack before the camera reports the new zoom.
     */
    fun linearToRatio(linear: Float, minRatio: Float, maxRatio: Float): Float {
        if (!canZoom(minRatio, maxRatio)) return minRatio
        if (linear >= 1f) return maxRatio
        if (linear <= 0f) return minRatio
        val minCrop = 1.0 / minRatio
        val maxCrop = 1.0 / maxRatio
        val crop = minCrop + (maxCrop - minCrop) * linear
        return (1.0 / crop).toFloat().coerceIn(minRatio, maxRatio)
    }

    /** The inverse of [linearToRatio]. */
    fun ratioToLinear(ratio: Float, minRatio: Float, maxRatio: Float): Float {
        if (!canZoom(minRatio, maxRatio)) return 0f
        val clamped = ratio.coerceIn(minRatio, maxRatio)
        val minCrop = 1.0 / minRatio
        val maxCrop = 1.0 / maxRatio
        return ((minCrop - 1.0 / clamped) / (minCrop - maxCrop)).toFloat().coerceIn(0f, 1f)
    }

    /** The zoom ratio after a pinch that scaled the fingers' span by [scaleFactor]. */
    fun pinchRatio(currentRatio: Float, scaleFactor: Float, minRatio: Float, maxRatio: Float): Float =
        (currentRatio * scaleFactor).coerceIn(minRatio, maxRatio)

    /**
     * The ratio as shown and spoken: two decimals below 2x ("1.04"), where one zoom step can be
     * less than 0.1, and one decimal from 2x up ("3.2"), where every step is more.
     */
    fun formatRatio(ratio: Float, locale: Locale): String =
        String.format(locale, if (ratio < TWO_DECIMALS_BELOW) "%.2f" else "%.1f", ratio)

    /** The zoom label has two decimals below this ratio. */
    const val TWO_DECIMALS_BELOW = 1.995f

    /** A distance in metres as spoken: one decimal ("1.5"). */
    fun formatOneDecimal(value: Float, locale: Locale): String = String.format(locale, "%.1f", value)
}
