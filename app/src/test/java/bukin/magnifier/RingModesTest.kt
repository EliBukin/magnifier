package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the ring adjusts: the Brightness and Focus buttons, and what can be on it when. */
class RingModesTest {

    @Test
    fun theBrightnessButtonPutsBrightnessOnTheRingAndTakesItOff() {
        assertEquals(RingMode.BRIGHTNESS, RingModes.brightnessButton(RingMode.ZOOM))
        assertEquals(RingMode.BRIGHTNESS, RingModes.brightnessButton(RingMode.FOCUS))
        assertEquals(RingMode.ZOOM, RingModes.brightnessButton(RingMode.BRIGHTNESS))
    }

    @Test
    fun withoutManualFocusTheFocusButtonIsTheOldToggle() {
        for (ring in RingMode.entries) {
            assertEquals(FocusButtonAction.LOCK_MIDDLE, RingModes.focusButton(FocusMode.AUTO, ring, manualSupported = false))
            assertEquals(FocusButtonAction.BACK_TO_AUTO, RingModes.focusButton(FocusMode.LOCKED, ring, manualSupported = false))
        }
    }

    @Test
    fun withManualFocusTheFocusButtonOpensTheFocusRingFromAuto() {
        assertEquals(FocusButtonAction.RING_TO_FOCUS, RingModes.focusButton(FocusMode.AUTO, RingMode.ZOOM, manualSupported = true))
        assertEquals(FocusButtonAction.RING_TO_FOCUS, RingModes.focusButton(FocusMode.AUTO, RingMode.BRIGHTNESS, manualSupported = true))
        assertEquals(FocusButtonAction.RING_TO_ZOOM, RingModes.focusButton(FocusMode.AUTO, RingMode.FOCUS, manualSupported = true))
        // Locked (a tap on the picture) or Manual: one tap back to Auto, wherever the ring is.
        for (ring in RingMode.entries) {
            assertEquals(FocusButtonAction.BACK_TO_AUTO, RingModes.focusButton(FocusMode.LOCKED, ring, manualSupported = true))
            assertEquals(FocusButtonAction.BACK_TO_AUTO, RingModes.focusButton(FocusMode.MANUAL, ring, manualSupported = true))
        }
    }

    @Test
    fun theRingShowsOnlyWhatCanChangeNow() {
        // Focus: only with manual focus, and not frozen.
        assertEquals(RingMode.FOCUS, RingModes.allowed(RingMode.FOCUS, manualSupported = true, frozen = false, brightnessAvailable = true))
        assertEquals(RingMode.ZOOM, RingModes.allowed(RingMode.FOCUS, manualSupported = false, frozen = false, brightnessAvailable = true))
        assertEquals(RingMode.ZOOM, RingModes.allowed(RingMode.FOCUS, manualSupported = true, frozen = true, brightnessAvailable = true))
        // Brightness: live only with the camera's exposure; frozen always.
        assertEquals(RingMode.BRIGHTNESS, RingModes.allowed(RingMode.BRIGHTNESS, false, frozen = false, brightnessAvailable = true))
        assertEquals(RingMode.ZOOM, RingModes.allowed(RingMode.BRIGHTNESS, false, frozen = false, brightnessAvailable = false))
        assertEquals(RingMode.BRIGHTNESS, RingModes.allowed(RingMode.BRIGHTNESS, false, frozen = true, brightnessAvailable = false))
        assertEquals(RingMode.ZOOM, RingModes.allowed(RingMode.ZOOM, false, frozen = true, brightnessAvailable = false))
    }

    @Test
    fun theLevelReadsWithItsSign() {
        assertEquals("0", RingModes.levelText(0))
        assertEquals("+3", RingModes.levelText(3))
        assertEquals("−2", RingModes.levelText(-2))
    }
}
