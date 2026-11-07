package bukin.magnifier

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The lens ring's shape, in plain numbers so it can be unit-tested: an arc of the circle around
 * ([cx], [cy]) with [radius], [sweep] degrees wide, its middle straight up.
 *
 * Angles are degrees from straight up, clockwise positive (screen coordinates, y down): the arc
 * runs from -sweep/2 (its left end) to +sweep/2 (its right end).
 *
 * A *fraction* is a place along the ring, 0 at its start and 1 at its end. The start is the left
 * end, or the right end in a right-to-left language ([rtl]), as the sliders were: zoom out, darker
 * and near are on the right in Hebrew.
 */
data class RingGeometry(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val sweep: Float,
    /** Half the touch band's thickness: a touch this close to the track's line is on the ring. */
    val bandHalf: Float,
    val rtl: Boolean,
    /** Touches this many degrees beyond either end still count (the thumb sticks out there). */
    val endSlack: Float = END_SLACK,
) {
    val half: Float get() = sweep / 2f

    /** The angle of a point seen from the centre. */
    fun angleAt(x: Float, y: Float): Float = Math.toDegrees(atan2((x - cx).toDouble(), (cy - y).toDouble())).toFloat()

    fun distanceAt(x: Float, y: Float): Float = hypot(x - cx, y - cy)

    /** Whether a touch at (x, y) is on the ring's band, between its ends. */
    fun inBand(x: Float, y: Float): Boolean =
        abs(distanceAt(x, y) - radius) <= bandHalf && abs(angleAt(x, y)) <= half + endSlack

    /** The place along the ring at [angle], clamped to the ends. */
    fun fractionAt(angle: Float): Float {
        val f = ((angle + half) / sweep).coerceIn(0f, 1f)
        return if (rtl) 1f - f else f
    }

    /** The angle of a place along the ring. */
    fun angleFor(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        return -half + (if (rtl) 1f - f else f) * sweep
    }

    fun xFor(fraction: Float, r: Float = radius): Float = cx + r * sin(Math.toRadians(angleFor(fraction).toDouble())).toFloat()

    fun yFor(fraction: Float, r: Float = radius): Float = cy - r * cos(Math.toRadians(angleFor(fraction).toDouble())).toFloat()

    /**
     * How far along the ring a finger turned it, moving from [fromAngle] to [toAngle]: the whole
     * ring is 1. Positive is towards the end. Works across the bottom (where the angle wraps).
     */
    fun fractionDelta(fromAngle: Float, toAngle: Float): Float {
        var d = toAngle - fromAngle
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        val f = d / sweep
        return if (rtl) -f else f
    }

    /**
     * A tap on the band at (x, y), with the thumb at [thumbFraction]: -1 steps towards the start
     * (a tap on the thumb's start side), +1 towards the end, 0 on the thumb itself
     * ([thumbRadius] around it).
     */
    fun tapSteps(x: Float, y: Float, thumbFraction: Float, thumbRadius: Float): Int {
        val tap = angleAt(x, y)
        val thumb = angleFor(thumbFraction)
        val thumbHalfAngle = Math.toDegrees((thumbRadius / radius).toDouble()).toFloat()
        if (abs(tap - thumb) <= thumbHalfAngle) return 0
        // Beyond either end counts as that end.
        val towardsEnd = fractionAt(tap) > thumbFraction ||
            (fractionAt(tap) == thumbFraction && fractionDelta(thumb, tap) > 0f)
        return if (towardsEnd) 1 else -1
    }

    companion object {
        const val SWEEP = 150f
        const val END_SLACK = 5f

        /**
         * The biggest ring that fits a view [width] wide (all sizes in pixels): its ends and their
         * thumb inside the view's sides, at most [maxRadius]. The band's outer edge touches the
         * view's top at the ring's middle.
         */
        fun fit(
            width: Float,
            bandHalf: Float,
            thumbRadius: Float,
            sideMargin: Float,
            maxRadius: Float,
            rtl: Boolean,
            sweep: Float = SWEEP,
        ): RingGeometry {
            val halfRad = Math.toRadians(sweep / 2.0)
            val fitRadius = ((width / 2f - thumbRadius - sideMargin) / sin(halfRad)).toFloat()
            val radius = min(maxRadius, fitRadius).coerceAtLeast(1f)
            return RingGeometry(width / 2f, bandHalf + radius, radius, sweep, bandHalf, rtl)
        }

        /** How tall the ring's view is: down to just below the thumb at its ends. */
        fun heightFor(ring: RingGeometry, thumbRadius: Float, gap: Float): Float =
            ring.cy - ring.radius * cos(Math.toRadians(ring.half.toDouble())).toFloat() + thumbRadius + gap
    }
}

/** What the ring adjusts. Zoom unless the user chose Brightness or Focus. */
enum class RingMode { ZOOM, BRIGHTNESS, FOCUS }

/** What a tap on the focus button does (see [RingModes.focusButton]). */
enum class FocusButtonAction {
    /** No manual focus on this phone, Auto: lock the focus on the middle of the picture. */
    LOCK_MIDDLE,

    /** Locked or Manual: back to auto focus (the ring stays where it is). */
    BACK_TO_AUTO,

    /** Auto, the ring on zoom or brightness: the ring sets the focus distance. */
    RING_TO_FOCUS,

    /** Auto, the ring on the focus: the ring goes back to zoom. */
    RING_TO_ZOOM,
}

/** The rules for what the ring adjusts. */
object RingModes {
    /** The Brightness button: the ring to brightness, or back to zoom if it is there already. */
    fun brightnessButton(current: RingMode): RingMode = if (current == RingMode.BRIGHTNESS) RingMode.ZOOM else RingMode.BRIGHTNESS

    /**
     * The Focus button. Without manual focus it is the Auto / Locked toggle it always was. With
     * it, Locked and Manual go back to Auto in one tap (the most common need after a tap on the
     * picture); in Auto it puts the focus distance on the ring, and takes it off again.
     */
    fun focusButton(focus: FocusMode, ring: RingMode, manualSupported: Boolean): FocusButtonAction = when {
        focus != FocusMode.AUTO -> FocusButtonAction.BACK_TO_AUTO
        !manualSupported -> FocusButtonAction.LOCK_MIDDLE
        ring == RingMode.FOCUS -> FocusButtonAction.RING_TO_ZOOM
        else -> FocusButtonAction.RING_TO_FOCUS
    }

    /**
     * The mode the ring can show now: the focus only with manual focus and not frozen;
     * brightness only where it can change (live, the camera's exposure; frozen, always).
     */
    fun allowed(wanted: RingMode, manualSupported: Boolean, frozen: Boolean, brightnessAvailable: Boolean): RingMode = when (wanted) {
        RingMode.FOCUS -> if (manualSupported && !frozen) RingMode.FOCUS else RingMode.ZOOM
        RingMode.BRIGHTNESS -> if (frozen || brightnessAvailable) RingMode.BRIGHTNESS else RingMode.ZOOM
        RingMode.ZOOM -> RingMode.ZOOM
    }

    /** The brightness level as shown: "+3", "−2" (a real minus sign), "0". */
    fun levelText(level: Int): String = when {
        level > 0 -> "+$level"
        level < 0 -> "−${-level}"
        else -> "0"
    }
}
