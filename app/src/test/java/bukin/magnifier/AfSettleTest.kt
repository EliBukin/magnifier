package bukin.magnifier

import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF
import android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_INACTIVE
import android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED
import android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN
import android.hardware.camera2.CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The step-2 focus fix: a tap after manual focus waits for continuous autofocus first. */
class AfSettleTest {

    private val cont = CONTROL_AF_MODE_CONTINUOUS_PICTURE

    @Test
    fun locksOnceContinuousAutofocusHasScannedAndFound() {
        val settle = AfSettle(startMs = 1000)
        // Still the manual frames: AF off.
        assertFalse(settle.onFrame(CONTROL_AF_MODE_OFF, CONTROL_AF_STATE_INACTIVE, 1030))
        // Continuous again, scanning from the near lens position.
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_INACTIVE, 1060))
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_SCAN, 1100))
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_SCAN, 1300))
        assertTrue(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, 1350))
        assertTrue(settle.outcome.startsWith("settled after 350 ms"))
        // Exactly once.
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, 1400))
        assertFalse(settle.onTimeout(5000))
    }

    @Test
    fun anUnsuccessfulScanAlsoCounts() {
        val settle = AfSettle(startMs = 0)
        settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_SCAN, 50)
        assertTrue(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 600))
    }

    @Test
    fun aFinishedStateRightAwayWaitsForTheQuietTime() {
        // No scan seen: the camera may still be reporting the old state.
        val settle = AfSettle(startMs = 0)
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, 150))
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, 399))
        assertTrue(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, AfSettle.QUIET_MS))
    }

    @Test
    fun notBeforeTheMinimumWait() {
        val settle = AfSettle(startMs = 0)
        settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_SCAN, 10)
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, 50))
        assertTrue(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, AfSettle.MIN_WAIT_MS))
    }

    @Test
    fun framesInAnotherModeNeverCount() {
        val settle = AfSettle(startMs = 0)
        assertFalse(settle.onFrame(CONTROL_AF_MODE_AUTO, CONTROL_AF_STATE_PASSIVE_FOCUSED, 900))
        assertFalse(settle.onFrame(null, null, 900))
        assertFalse(settle.onFrame(cont, null, 900))
    }

    @Test
    fun afterTheTimeoutTheLockGoesAheadAnyway() {
        val settle = AfSettle(startMs = 0)
        settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_SCAN, 100)
        assertFalse(settle.onTimeout(AfSettle.TIMEOUT_MS - 1))
        assertTrue(settle.onTimeout(AfSettle.TIMEOUT_MS))
        assertTrue(settle.outcome.startsWith("timed out"))
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, AfSettle.TIMEOUT_MS + 10))
        assertFalse(settle.onTimeout(AfSettle.TIMEOUT_MS + 100))
    }

    @Test
    fun aNewerFocusChangeCancelsTheWait() {
        val settle = AfSettle(startMs = 0)
        settle.cancel()
        assertFalse(settle.onFrame(cont, CONTROL_AF_STATE_PASSIVE_FOCUSED, 500))
        assertFalse(settle.onTimeout(AfSettle.TIMEOUT_MS))
        assertEquals("cancelled", settle.outcome)
    }
}
