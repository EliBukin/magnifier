package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The four colour matrices on sample pixels, alone and with the frozen brightness. */
class ColorModeTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = rgb(v, v, v)
    private fun ColorMode.on(pixel: Int, gain: Float = 1f) = ColorMode.apply(matrix(gain), pixel)
    private fun channels(argb: Int) = listOf((argb ushr 24) and 0xFF, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private val white = grey(255)
    private val black = grey(0)
    private val paper = grey(200)
    private val ink = grey(60)

    @Test
    fun normalLeavesColoursAlone() {
        for (pixel in listOf(white, black, paper, ink, rgb(10, 200, 30), rgb(255, 0, 0))) {
            assertEquals(pixel, ColorMode.NORMAL.on(pixel))
        }
    }

    @Test
    fun highContrastIsGreyWithTheContrastDoubled() {
        assertEquals(white, ColorMode.HIGH_CONTRAST.on(white))
        assertEquals(black, ColorMode.HIGH_CONTRAST.on(black))
        assertEquals(grey(128), ColorMode.HIGH_CONTRAST.on(grey(128)))
        // Paper goes white, ink black.
        assertEquals(white, ColorMode.HIGH_CONTRAST.on(paper))
        assertEquals(black, ColorMode.HIGH_CONTRAST.on(ink))
        assertEquals(grey(2 * 100 - 128), ColorMode.HIGH_CONTRAST.on(grey(100)))
        // Colours become grey: pure green is bright (0.7152 * 255 = 182 -> 2 * 182 - 128 = 237).
        assertEquals(grey(237), ColorMode.HIGH_CONTRAST.on(rgb(0, 255, 0)))
        assertEquals(black, ColorMode.HIGH_CONTRAST.on(rgb(255, 0, 0)))
    }

    @Test
    fun invertedIsAColourNegative() {
        assertEquals(black, ColorMode.INVERTED.on(white))
        assertEquals(white, ColorMode.INVERTED.on(black))
        assertEquals(rgb(245, 55, 225), ColorMode.INVERTED.on(rgb(10, 200, 30)))
        assertEquals(grey(55), ColorMode.INVERTED.on(paper))
    }

    @Test
    fun yellowOnBlackTurnsInkYellowAndPaperBlack() {
        val yellow = rgb(255, 255, 0)
        assertEquals(black, ColorMode.YELLOW_ON_BLACK.on(paper))
        assertEquals(black, ColorMode.YELLOW_ON_BLACK.on(white))
        assertEquals(yellow, ColorMode.YELLOW_ON_BLACK.on(ink))
        assertEquals(yellow, ColorMode.YELLOW_ON_BLACK.on(black))
        // The middle grey stays in the middle: 2 * (127 - 128) + 128 = 126.
        assertEquals(rgb(126, 126, 0), ColorMode.YELLOW_ON_BLACK.on(grey(128)))
        // Never any blue, and red always equals green.
        for (v in 0..255 step 5) {
            val (_, r, g, b) = channels(ColorMode.YELLOW_ON_BLACK.on(rgb(v, 255 - v, v / 2)))
            assertEquals(0, b)
            assertEquals(r, g)
        }
    }

    @Test
    fun alphaStaysOpaque() {
        for (mode in ColorMode.entries) {
            assertEquals(0xFF, channels(mode.on(rgb(12, 34, 56), gain = 2f))[0])
        }
    }

    @Test
    fun brightnessScalesTheCameraColoursBeforeTheMode() {
        // Normal: darker and lighter, clamped at white.
        assertEquals(grey(100), ColorMode.NORMAL.on(paper, gain = 0.5f))
        assertEquals(white, ColorMode.NORMAL.on(paper, gain = 2f))
        // Inverted: more light on the page makes the inverted page darker, as live exposure does.
        assertEquals(grey(255 - 100), ColorMode.INVERTED.on(grey(50), gain = 2f))
        // High contrast: a dim page (paper at 140) turns white once lightened.
        assertEquals(grey(2 * 140 - 128), ColorMode.HIGH_CONTRAST.on(grey(140)))
        assertEquals(white, ColorMode.HIGH_CONTRAST.on(grey(140), gain = Brightness.gain(5)))
        // Yellow on black: the same dim page's paper goes from grey to black.
        assertEquals(rgb(102, 102, 0), ColorMode.YELLOW_ON_BLACK.on(grey(140)))
        assertEquals(black, ColorMode.YELLOW_ON_BLACK.on(grey(140), gain = Brightness.gain(5)))
    }

    @Test
    fun theButtonCyclesThroughAllFourAndBack() {
        var mode = ColorMode.NORMAL
        val seen = mutableListOf<ColorMode>()
        repeat(4) {
            seen += mode
            mode = mode.next()
        }
        assertEquals(ColorMode.entries.toList(), seen)
        assertEquals(ColorMode.NORMAL, mode)
    }

    @Test
    fun anUnknownSavedNameIsNormal() {
        assertEquals(ColorMode.YELLOW_ON_BLACK, ColorMode.fromName("YELLOW_ON_BLACK"))
        assertEquals(ColorMode.NORMAL, ColorMode.fromName(null))
        assertEquals(ColorMode.NORMAL, ColorMode.fromName("SEPIA"))
    }

    @Test
    fun frozenBrightnessIsAGainOfUpToOneAndAHalfStops() {
        assertEquals(1f, Brightness.gain(0), 0f)
        assertEquals(2f.pow(1.5f), Brightness.gain(10), 1e-4f)
        assertEquals(2f.pow(-1.5f), Brightness.gain(-10), 1e-4f)
        assertEquals(Brightness.gain(10), Brightness.gain(99), 0f)
        for (level in -10 until 10) assertTrue(Brightness.gain(level) < Brightness.gain(level + 1))
    }

    @Test
    fun theA20sExposureRangeGetsTwentySteps() {
        // The A20: -20..20 in tenths of a stop.
        val a20 = Brightness.Exposure.of(-20, 20)!!
        assertEquals(Brightness.Exposure(-20, 20, 2), a20)
        assertEquals(4, a20.indexFor(3f))
        assertEquals(-20, a20.indexFor(-99f))
        assertEquals(2, a20.stepFrom(0, 1))
        assertEquals(20, a20.stepFrom(19, 5))
        assertEquals(-3, a20.level(-6))
        assertEquals(0f, a20.valueFor(0), 0f)
        assertEquals(4f, a20.valueFor(3), 0f)
    }

    @Test
    fun smallAndOddRangesKeepEveryStepDistinctAndTheMiddleOnTheGrid() {
        assertEquals(Brightness.Exposure(-6, 6, 1), Brightness.Exposure.of(-6, 6))
        assertEquals(Brightness.Exposure(-9, 12, 1), Brightness.Exposure.of(-9, 12))
        assertEquals(Brightness.Exposure(-30, 30, 3), Brightness.Exposure.of(-30, 30))
        // -13..13 in steps of 1 (26 / 20 rounds to 1).
        assertEquals(Brightness.Exposure(-13, 13, 1), Brightness.Exposure.of(-13, 13))
        // 50 / 20 rounds to 3 (2.5 up): the grid keeps 0 and stays inside -24..26.
        val odd = Brightness.Exposure.of(-24, 26)!!
        assertEquals(Brightness.Exposure(-24, 24, 3), odd)
        for (range in listOf(Brightness.Exposure.of(-6, 6)!!, Brightness.Exposure.of(-20, 20)!!, odd)) {
            var index = range.from
            val visited = mutableListOf(index)
            while (index < range.to) {
                index = range.stepFrom(index, 1)
                visited += index
            }
            assertEquals(visited.distinct(), visited)
            assertTrue(0 in visited)
        }
    }

    @Test
    fun aCameraWithoutExposureCompensationHasNoLiveSlider() {
        assertNull(Brightness.Exposure.of(0, 0))
        assertNull(Brightness.Exposure.of(0, 4))
        assertNull(Brightness.Exposure.of(-4, 0))
    }
}
