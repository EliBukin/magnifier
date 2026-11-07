package bukin.magnifier

import androidx.camera.core.TorchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchButtonStateTest {

    @Test
    fun beforeTheCameraIsBoundTheButtonWaits() {
        val state = TorchButtonState.from(hasFlashUnit = null, torchState = null)
        assertEquals(TorchButtonState.WAITING, state)
        assertFalse(state.enabled)
        // Even if a torch state somehow arrived first.
        assertEquals(TorchButtonState.WAITING, TorchButtonState.from(null, TorchState.ON))
    }

    @Test
    fun noFlashUnitDisablesTheButtonWithAReason() {
        for (torch in listOf(null, TorchState.OFF, TorchState.ON)) {
            val state = TorchButtonState.from(hasFlashUnit = false, torchState = torch)
            assertEquals(TorchButtonState.NO_FLASH, state)
            assertFalse(state.enabled)
            assertEquals(R.string.light_none, state.text)
            assertEquals(R.string.light_none_desc, state.contentDescription)
        }
    }

    @Test
    fun theButtonFollowsTheCamerasTorchState() {
        val off = TorchButtonState.from(hasFlashUnit = true, torchState = TorchState.OFF)
        assertEquals(TorchButtonState.OFF, off)
        assertTrue(off.enabled)
        assertTrue(off.tapTurnsOn)
        assertFalse(off.isOn)
        assertEquals(R.string.state_off, off.stateDescription)
        assertEquals(R.drawable.ic_light_off, off.icon)

        val on = TorchButtonState.from(hasFlashUnit = true, torchState = TorchState.ON)
        assertEquals(TorchButtonState.ON, on)
        assertTrue(on.enabled)
        assertFalse(on.tapTurnsOn)
        assertTrue(on.isOn)
        assertEquals(R.string.state_on, on.stateDescription)
        assertEquals(R.drawable.ic_light_on, on.icon)
    }

    @Test
    fun aFlashCameraWithNoTorchReportYetShowsOff() {
        assertEquals(TorchButtonState.OFF, TorchButtonState.from(hasFlashUnit = true, torchState = null))
    }

    @Test
    fun everyStateHasALabelForTalkBack() {
        for (state in TorchButtonState.values()) {
            assertTrue(state.text != 0)
            assertTrue(state.contentDescription != 0)
            assertEquals(state.enabled, state.stateDescription != 0)
        }
    }
}
