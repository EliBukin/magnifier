package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ZoomMathTest {

    private val eps = 1e-4f

    @Test
    fun sliderMapsToLinearZoomAndBack() {
        assertEquals(0f, ZoomMath.sliderToLinear(0f), eps)
        assertEquals(0.37f, ZoomMath.sliderToLinear(37f), eps)
        assertEquals(1f, ZoomMath.sliderToLinear(100f), eps)
        assertEquals(37f, ZoomMath.linearToSlider(0.37f), eps)
        for (v in 0..100) {
            assertEquals(v.toFloat(), ZoomMath.linearToSlider(ZoomMath.sliderToLinear(v.toFloat())), eps)
        }
    }

    @Test
    fun sliderMappingClampsOutOfRangeValues() {
        assertEquals(0f, ZoomMath.sliderToLinear(-5f), eps)
        assertEquals(1f, ZoomMath.sliderToLinear(130f), eps)
        assertEquals(0f, ZoomMath.linearToSlider(-0.1f), eps)
        assertEquals(100f, ZoomMath.linearToSlider(1.2f), eps)
    }

    @Test
    fun theStepIsATwentiethOfTheRange() {
        assertEquals(0.05f, ZoomMath.STEP, 0f)
        assertEquals(100f, ZoomMath.SLIDER_MAX, 0f)
    }

    @Test
    fun stepsMoveOneGridPointFromAGridPoint() {
        assertEquals(0.45f, ZoomMath.stepLinear(0.40f, 1), eps)
        assertEquals(0.35f, ZoomMath.stepLinear(0.40f, -1), eps)
        assertEquals(0.55f, ZoomMath.stepLinear(0.40f, 3), eps)
        assertEquals(0.05f, ZoomMath.stepLinear(0f, 1), eps)
    }

    @Test
    fun stepsFromBetweenGridPointsGoToTheNextGridPoint() {
        assertEquals(0.40f, ZoomMath.stepLinear(0.37f, 1), eps)
        assertEquals(0.35f, ZoomMath.stepLinear(0.37f, -1), eps)
    }

    @Test
    fun floatErrorDoesNotSkipOrRepeatAStep() {
        // CameraX may report 0.39999 for 0.40.
        assertEquals(0.45f, ZoomMath.stepLinear(0.39999f, 1), eps)
        assertEquals(0.35f, ZoomMath.stepLinear(0.40001f, -1), eps)
    }

    @Test
    fun stepsStopAtTheEnds() {
        assertEquals(1f, ZoomMath.stepLinear(1f, 1), eps)
        assertEquals(1f, ZoomMath.stepLinear(0.98f, 1), eps)
        assertEquals(0f, ZoomMath.stepLinear(0f, -1), eps)
        assertEquals(0f, ZoomMath.stepLinear(0.02f, -1), eps)
        assertEquals(1f, ZoomMath.stepLinear(0.5f, 100), eps)
    }

    @Test
    fun twentyStepsCrossTheWholeRange() {
        var linear = 0f
        repeat(20) { linear = ZoomMath.stepLinear(linear, 1) }
        assertEquals(1f, linear, eps)
        repeat(20) { linear = ZoomMath.stepLinear(linear, -1) }
        assertEquals(0f, linear, eps)
    }

    @Test
    fun linearZoomMovesTheCropWidthEvenly() {
        // 1x..8x: the visible width goes 1 -> 1/8, evenly with linear zoom.
        assertEquals(1f, ZoomMath.linearToRatio(0f, 1f, 8f), eps)
        assertEquals(8f, ZoomMath.linearToRatio(1f, 1f, 8f), eps)
        val half = ZoomMath.linearToRatio(0.5f, 1f, 8f)
        assertEquals(1f / ((1f + 1f / 8f) / 2f), half, eps)
        assertEquals(1.7778f, half, 1e-3f)
    }

    @Test
    fun ratioAndLinearAreInverse() {
        for (min in listOf(1f, 0.6f)) {
            for (i in 0..20) {
                val linear = i / 20f
                val ratio = ZoomMath.linearToRatio(linear, min, 10f)
                assertEquals(linear, ZoomMath.ratioToLinear(ratio, min, 10f), 1e-3f)
            }
        }
    }

    @Test
    fun aCameraWithoutZoomStaysAtItsOnlyRatio() {
        assertFalse(ZoomMath.canZoom(1f, 1f))
        assertTrue(ZoomMath.canZoom(1f, 4f))
        assertEquals(1f, ZoomMath.linearToRatio(0.7f, 1f, 1f), eps)
        assertEquals(0f, ZoomMath.ratioToLinear(1f, 1f, 1f), eps)
    }

    @Test
    fun pinchScalesTheRatioWithinRange() {
        assertEquals(3f, ZoomMath.pinchRatio(2f, 1.5f, 1f, 8f), eps)
        assertEquals(1f, ZoomMath.pinchRatio(1.2f, 0.5f, 1f, 8f), eps)
        assertEquals(8f, ZoomMath.pinchRatio(6f, 2f, 1f, 8f), eps)
    }

    @Test
    fun ratioIsShownWithOneDecimal() {
        assertEquals("3.2", ZoomMath.formatRatio(3.2f, Locale.US))
        assertEquals("1.0", ZoomMath.formatRatio(1f, Locale.US))
        assertEquals("8.0", ZoomMath.formatRatio(7.96f, Locale.US))
        assertEquals("3.2", ZoomMath.formatRatio(3.2f, Locale.forLanguageTag("iw")))
    }
}
