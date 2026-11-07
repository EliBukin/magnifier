package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusModeTest {

    private fun FocusMode.after(event: FocusEvent, manual: Boolean = true) = next(event, manualSupported = manual)

    @Test
    fun aTapOnThePictureLocksTheFocusFromAnyMode() {
        for (mode in FocusMode.values()) {
            assertEquals(FocusMode.LOCKED, mode.after(FocusEvent.TAP_PICTURE))
            assertEquals(FocusMode.LOCKED, mode.after(FocusEvent.TAP_PICTURE, manual = false))
        }
    }

    @Test
    fun theFocusButtonGoesBackToAuto() {
        assertEquals(FocusMode.AUTO, FocusMode.LOCKED.after(FocusEvent.FOCUS_BUTTON))
        assertEquals(FocusMode.AUTO, FocusMode.MANUAL.after(FocusEvent.FOCUS_BUTTON))
    }

    @Test
    fun inAutoTheFocusButtonLocksTheFocus() {
        // On the middle of the picture: the button is a toggle, also for TalkBack users.
        assertEquals(FocusMode.LOCKED, FocusMode.AUTO.after(FocusEvent.FOCUS_BUTTON))
        assertEquals(FocusMode.LOCKED, FocusMode.AUTO.after(FocusEvent.FOCUS_BUTTON, manual = false))
    }

    @Test
    fun theSliderSwitchesToManualOnlyWhereSupported() {
        for (mode in FocusMode.values()) {
            assertEquals(FocusMode.MANUAL, mode.after(FocusEvent.FOCUS_SLIDER, manual = true))
            assertEquals(mode, mode.after(FocusEvent.FOCUS_SLIDER, manual = false))
        }
    }

    @Test
    fun leavingTheAppOrRestartingTheCameraReturnsToAuto() {
        for (mode in FocusMode.values()) {
            assertEquals(FocusMode.AUTO, mode.after(FocusEvent.LEFT_APP))
            assertEquals(FocusMode.AUTO, mode.after(FocusEvent.CAMERA_RESTARTED))
        }
    }

    @Test
    fun aTypicalSession() {
        var mode = FocusMode.AUTO
        mode = mode.after(FocusEvent.TAP_PICTURE)
        assertEquals(FocusMode.LOCKED, mode)
        mode = mode.after(FocusEvent.FOCUS_SLIDER)
        assertEquals(FocusMode.MANUAL, mode)
        mode = mode.after(FocusEvent.TAP_PICTURE)
        assertEquals(FocusMode.LOCKED, mode)
        mode = mode.after(FocusEvent.FOCUS_BUTTON)
        assertEquals(FocusMode.AUTO, mode)
    }

    @Test
    fun onlyAutoLooksLikeTheDefault() {
        assertFalse(FocusMode.AUTO.highlighted)
        assertTrue(FocusMode.LOCKED.highlighted)
        assertTrue(FocusMode.MANUAL.highlighted)
    }

    @Test
    fun everyModeHasItsOwnTextIconAndAction() {
        assertEquals(R.string.focus_auto, FocusMode.AUTO.text)
        assertEquals(R.string.focus_locked, FocusMode.LOCKED.text)
        assertEquals(R.string.focus_manual, FocusMode.MANUAL.text)
        assertEquals(3, FocusMode.values().map { it.icon }.toSet().size)
        assertEquals(R.string.focus_action_lock, FocusMode.AUTO.buttonAction)
        for (mode in listOf(FocusMode.LOCKED, FocusMode.MANUAL)) {
            assertEquals(R.string.focus_action_auto, mode.buttonAction)
            assertNotEquals(0, mode.icon)
        }
    }
}
