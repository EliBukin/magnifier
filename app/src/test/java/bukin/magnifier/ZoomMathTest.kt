package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
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
    fun aStepFromJustOffTheGridAlwaysShows() {
        // After a ring drag to the middle the A20 echoes linear 0.4995 (1.5975x for 1.6x).
        assertEquals(0.55f, ZoomMath.stepLinear(0.4995f, 1), eps)
        assertEquals(0.45f, ZoomMath.stepLinear(0.5013f, -1), eps)
        // Every step from anywhere moves a quarter step at least, one and a quarter at most.
        var start = 0f
        while (start <= 0.9f) {
            for (dir in listOf(1, -1)) {
                val moved = abs(ZoomMath.stepLinear(start, dir) - start)
                if (start > 0.06f) {
                    assertTrue("from $start by $dir: $moved", moved >= ZoomMath.STEP / 4 - 1e-5f && moved <= ZoomMath.STEP * 1.25f + 1e-5f)
                }
            }
            start += 0.0037f
        }
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
    fun ratioHasTwoDecimalsBelowTwoAndOneFromTwoUp() {
        assertEquals("1.04", ZoomMath.formatRatio(1.039f, Locale.US))
        assertEquals("1.00", ZoomMath.formatRatio(1f, Locale.US))
        assertEquals("1.99", ZoomMath.formatRatio(1.99f, Locale.US))
        // Never "2.00": from just under 2 it's one decimal.
        assertEquals("2.0", ZoomMath.formatRatio(1.996f, Locale.US))
        assertEquals("3.2", ZoomMath.formatRatio(3.2f, Locale.US))
        assertEquals("8.0", ZoomMath.formatRatio(7.96f, Locale.US))
        assertEquals("1.25", ZoomMath.formatRatio(1.25f, Locale.forLanguageTag("iw")))
        assertEquals("3.2", ZoomMath.formatRatio(3.2f, Locale.forLanguageTag("iw")))
    }

    @Test
    fun everyZoomStepChangesTheNumberShown() {
        // The A20 (1-4x) and other phones' ranges, from narrow to wide-angle.
        val ranges = listOf(1f to 4f, 1f to 8f, 1f to 10f, 0.6f to 10f, 0.5f to 30f, 1f to 2.5f, 1f to 2.1f, 1f to 1.5f)
        for ((min, max) in ranges) {
            var linear = 0f
            var label = ZoomMath.formatRatio(ZoomMath.linearToRatio(linear, min, max), Locale.US)
            repeat(20) {
                linear = ZoomMath.stepLinear(linear, 1)
                val next = ZoomMath.formatRatio(ZoomMath.linearToRatio(linear, min, max), Locale.US)
                assertTrue("$min-$max: step to $linear still shows $label", next != label)
                label = next
            }
        }
    }

    @Test
    fun theFrozenPicturesStepsChangeTheNumberToo() {
        // Frozen zoom shown = the camera's zoom x the frozen scale; each step is about 1.11x.
        for (cameraZoom in listOf(1f, 1.04f, 2.5f, 4f)) {
            var scale = FrozenZoom.LOW
            var label = ZoomMath.formatRatio(cameraZoom * scale, Locale.US)
            while (scale < FrozenZoom.HIGH) {
                scale = FrozenZoom.step(scale, 1)
                val next = ZoomMath.formatRatio(cameraZoom * scale, Locale.US)
                assertTrue("camera $cameraZoom: frozen step to $scale still shows $label", next != label)
                label = next
            }
        }
    }

    @Test
    fun distancesInMetresHaveOneDecimal() {
        assertEquals("1.5", ZoomMath.formatOneDecimal(1.5f, Locale.US))
        assertEquals("2.0", ZoomMath.formatOneDecimal(2f, Locale.US))
    }
}
