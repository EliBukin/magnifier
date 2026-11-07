package bukin.magnifier

import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF
import bukin.magnifier.ManualFocusGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFocusGuardTest {

    private val off = CONTROL_AF_MODE_OFF

    /** A guard for a 10-diopter lens (like the A20's), already asked for [diopters] at t = 0. */
    private fun asked(diopters: Float = 5f) = ManualFocusGuard(minimumFocusDiopters = 10f).apply { onRequest(diopters, 0L) }

    @Test
    fun itWaitsUntilManualFocusIsUsed() {
        val guard = ManualFocusGuard(10f)
        assertEquals(State.IDLE, guard.state)
        // Frames before any manual focus decide nothing, however long it takes.
        assertEquals(State.IDLE, guard.onResult(CONTROL_AF_MODE_CONTINUOUS_PICTURE, -1f, 60_000L))
        assertEquals(State.IDLE, guard.onTimeout(60_000L))
        guard.onRequest(5f, 60_000L)
        assertEquals(State.CHECKING, guard.state)
    }

    @Test
    fun anEchoWithinTheTimeConfirms() {
        val guard = asked(5f)
        // The first frames still show autofocus: the request hasn't reached the camera yet.
        assertEquals(State.CHECKING, guard.onResult(CONTROL_AF_MODE_CONTINUOUS_PICTURE, -1f, 30L))
        assertEquals(State.CONFIRMED, guard.onResult(off, 5f, 130L))
        assertNull(guard.failure)
    }

    @Test
    fun theA20sEchoConfirms() {
        // What the A20 reported in the forced test: afMode OFF and exactly the asked distance.
        for (d in listOf(10f, 5f, 0f)) {
            assertEquals(State.CONFIRMED, asked(d).onResult(off, d, 100L))
        }
    }

    @Test
    fun aSmallDifferenceStillCounts() {
        val guard = asked(5f)
        // 3% of a 10-diopter lens: 0.3 diopters either way.
        assertEquals(0.3f, guard.toleranceDiopters, 1e-6f)
        assertEquals(State.CONFIRMED, guard.onResult(off, 5.25f, 100L))
        assertEquals(State.CONFIRMED, asked(5f).onResult(off, 4.75f, 100L))
        // Never less than 0.1 diopters, even for a short-range lens.
        assertEquals(0.1f, ManualFocusGuard(2f).toleranceDiopters, 1e-6f)
    }

    @Test
    fun noDistanceMinusOneOrAnotherDistanceFailsAfterTheTimeout() {
        for (wrong in listOf(null, -1f, 8f, 0f)) {
            val guard = asked(5f)
            assertEquals(State.CHECKING, guard.onResult(off, wrong, 500L))
            assertEquals(State.FAILED, guard.onResult(off, wrong, ManualFocusGuard.TIMEOUT_MS))
            assertTrue(guard.failure!!.contains("focusDistance=$wrong"))
        }
    }

    @Test
    fun theRightDistanceWithAutofocusStillOnDoesNotCount() {
        val guard = asked(5f)
        assertEquals(State.CHECKING, guard.onResult(CONTROL_AF_MODE_AUTO, 5f, 100L))
        assertEquals(State.FAILED, guard.onResult(CONTROL_AF_MODE_AUTO, 5f, 1_000L))
        assertEquals(State.CHECKING, asked(5f).onResult(null, 5f, 100L))
    }

    @Test
    fun noFramesAtAllFailsWhenTheTimeIsUp() {
        val guard = asked(5f)
        assertEquals(State.CHECKING, guard.onTimeout(999L))
        assertEquals(State.FAILED, guard.onTimeout(1_000L))
        assertTrue(guard.failure!!.contains("no frame"))
    }

    @Test
    fun whileTheSliderIsDraggedAnyRecentRequestCounts() {
        val guard = asked(10f)
        guard.onRequest(9f, 100L)
        guard.onRequest(8f, 200L)
        // The frames lag behind the newest request.
        assertEquals(State.CONFIRMED, guard.onResult(off, 9f, 250L))
        // Later requests don't move the deadline: it is 1 s after the first.
        val slow = asked(10f)
        slow.onRequest(2f, 900L)
        assertEquals(State.FAILED, slow.onResult(off, 6f, 1_000L))
    }

    @Test
    fun theVerdictStaysForThisCamera() {
        val confirmed = asked(5f).apply { onResult(off, 5f, 100L) }
        confirmed.onRequest(1f, 5_000L)
        assertEquals(State.CONFIRMED, confirmed.onResult(off, 9f, 9_000L))
        val failed = asked(5f).apply { onTimeout(2_000L) }
        failed.onRequest(5f, 5_000L)
        assertEquals(State.FAILED, failed.onResult(off, 5f, 5_100L))
        // A new camera binding checks again.
        failed.reset()
        assertEquals(State.IDLE, failed.state)
        assertNull(failed.failure)
    }

    @Test
    fun leavingManualBeforeAVerdictChecksAgainNextTime() {
        val guard = asked(5f)
        guard.cancelCheck()
        assertEquals(State.IDLE, guard.state)
        // The old deadline is gone: a later manual focus gets a full second again.
        guard.onRequest(3f, 10_000L)
        assertEquals(State.CHECKING, guard.onResult(off, -1f, 10_500L))
        assertEquals(State.CONFIRMED, guard.onResult(off, 3f, 10_900L))
        // Cancelling has no effect once there is a verdict.
        guard.cancelCheck()
        assertEquals(State.CONFIRMED, guard.state)
    }
}
