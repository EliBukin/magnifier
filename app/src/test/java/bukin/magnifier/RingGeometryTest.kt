package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The lens ring's shape: where a touch is on it, both directions, taps beside the thumb. */
class RingGeometryTest {

    // A 348dp-wide ring (a 360dp phone minus the panel's padding), at 1 px per dp.
    private val ltr = RingGeometry.fit(width = 348f, bandHalf = 28f, thumbRadius = 16f, sideMargin = 2f, maxRadius = 220f, rtl = false)
    private val rtl = ltr.copy(rtl = true)

    /** The point at [angle] degrees from straight up, [r] from the centre. */
    private fun RingGeometry.at(angle: Float, r: Float = radius): Pair<Float, Float> {
        val rad = Math.toRadians(angle.toDouble())
        return (cx + r * sin(rad)).toFloat() to (cy - r * cos(rad)).toFloat()
    }

    @Test
    fun theRingFillsTheWidthWithItsEndsAndThumbInside() {
        assertEquals(150f, ltr.sweep, 0f)
        assertEquals(174f, ltr.cx, 1e-3f)
        assertEquals((174f - 16f - 2f) / sin(Math.toRadians(75.0)).toFloat(), ltr.radius, 1e-2f)
        // The band's outer edge touches the top at the middle.
        assertEquals(0f, ltr.cy - ltr.radius - ltr.bandHalf, 1e-3f)
        assertEquals(16f + 2f, ltr.xFor(0f) - 0f, 1e-2f)
        assertEquals(348f - 18f, ltr.xFor(1f), 1e-2f)
        // The view ends just below the thumb at the ends.
        val height = RingGeometry.heightFor(ltr, thumbRadius = 16f, gap = 4f)
        assertEquals(ltr.yFor(0f) + 20f, height, 1e-2f)
        // A wide screen keeps the ring at its biggest size, in the middle.
        val wide = RingGeometry.fit(1000f, 28f, 16f, 2f, 220f, rtl = false)
        assertEquals(220f, wide.radius, 0f)
        assertEquals(500f, wide.cx, 0f)
    }

    @Test
    fun theTouchBandIs56Thick() {
        assertTrue(ltr.inBand(ltr.at(0f, ltr.radius + 27f).first, ltr.at(0f, ltr.radius + 27f).second))
        assertTrue(ltr.inBand(ltr.at(0f, ltr.radius - 27f).first, ltr.at(0f, ltr.radius - 27f).second))
        assertFalse(ltr.inBand(ltr.at(0f, ltr.radius - 29f).first, ltr.at(0f, ltr.radius - 29f).second))
        assertTrue(2 * ltr.bandHalf >= 48f)
        // At the ends, and a little beyond (the thumb sticks out there); not further.
        assertTrue(ltr.at(-75f).let { ltr.inBand(it.first, it.second) })
        assertTrue(ltr.at(79f).let { ltr.inBand(it.first, it.second) })
        assertFalse(ltr.at(81f).let { ltr.inBand(it.first, it.second) })
        assertFalse(ltr.at(180f).let { ltr.inBand(it.first, it.second) })
        // The middle of the ring, where the value and Freeze are, is not the ring.
        assertFalse(ltr.inBand(ltr.cx, ltr.cy - 50f))
    }

    @Test
    fun aTouchAngleIsAPlaceAlongTheRingLeftToRight() {
        assertEquals(0f, ltr.angleAt(ltr.cx, ltr.cy - 100f), 1e-3f)
        assertEquals(90f, ltr.angleAt(ltr.cx + 100f, ltr.cy), 1e-3f)
        assertEquals(-90f, ltr.angleAt(ltr.cx - 100f, ltr.cy), 1e-3f)
        assertEquals(0f, ltr.fractionAt(-75f), 1e-5f)
        assertEquals(0.5f, ltr.fractionAt(0f), 1e-5f)
        assertEquals(1f, ltr.fractionAt(75f), 1e-5f)
        assertEquals(0.7f, ltr.fractionAt(30f), 1e-5f)
        // Beyond the ends: the ends.
        assertEquals(0f, ltr.fractionAt(-80f), 0f)
        assertEquals(1f, ltr.fractionAt(80f), 0f)
        // And back: a place's point is at its angle.
        for (f in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            assertEquals(f, ltr.fractionAt(ltr.angleAt(ltr.xFor(f), ltr.yFor(f))), 1e-4f)
        }
    }

    @Test
    fun rightToLeftTheRingStartsOnTheRight() {
        assertEquals(1f, rtl.fractionAt(-75f), 1e-5f)
        assertEquals(0f, rtl.fractionAt(75f), 1e-5f)
        assertEquals(0.3f, rtl.fractionAt(30f), 1e-5f)
        assertTrue(rtl.xFor(0f) > rtl.xFor(1f))
        assertEquals(ltr.xFor(0f), rtl.xFor(1f), 1e-3f)
    }

    @Test
    fun turningTheRingMovesByTheAngleBothWays() {
        // 15 degrees clockwise is a tenth of the 150-degree ring: towards the end, or the start in RTL.
        assertEquals(0.1f, ltr.fractionDelta(-20f, -5f), 1e-5f)
        assertEquals(-0.1f, ltr.fractionDelta(-5f, -20f), 1e-5f)
        assertEquals(-0.1f, rtl.fractionDelta(-20f, -5f), 1e-5f)
        assertEquals(0.1f, rtl.fractionDelta(-5f, -20f), 1e-5f)
        // Across the bottom, where the angle jumps from 180 to -180: still a small move.
        assertEquals(10f / 150f, ltr.fractionDelta(175f, -175f), 1e-5f)
    }

    @Test
    fun aTapBesideTheThumbStepsThatWay() {
        val thumb = 0.5f
        val left = ltr.at(-40f)
        val right = ltr.at(40f)
        val onThumb = ltr.at(ltr.angleFor(thumb) + 2f)
        assertEquals(-1, ltr.tapSteps(left.first, left.second, thumb, thumbRadius = 16f))
        assertEquals(1, ltr.tapSteps(right.first, right.second, thumb, thumbRadius = 16f))
        assertEquals(0, ltr.tapSteps(onThumb.first, onThumb.second, thumb, thumbRadius = 16f))
        // Mirrored: the left part is the end side in Hebrew.
        assertEquals(1, rtl.tapSteps(left.first, left.second, thumb, thumbRadius = 16f))
        assertEquals(-1, rtl.tapSteps(right.first, right.second, thumb, thumbRadius = 16f))
        // At an end: a tap on the ring's far part still points that way.
        val nearLeftEnd = ltr.at(-74f)
        assertEquals(-1, ltr.tapSteps(nearLeftEnd.first, nearLeftEnd.second, 0.3f, 16f))
        val beyondRight = ltr.at(78f)
        assertEquals(1, ltr.tapSteps(beyondRight.first, beyondRight.second, 0.95f, 16f))
    }
}
