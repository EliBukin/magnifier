package bukin.magnifier

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyZoomTest {

    @Test
    fun volumeUpZoomsInOneStep() {
        val result = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = 0.4f, canZoom = true)
        assertTrue(result.consumed)
        assertEquals(0.45f, result.newLinear!!, 1e-4f)
    }

    @Test
    fun volumeDownZoomsOutOneStep() {
        val result = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, cameraInFront = true, currentLinear = 0.4f, canZoom = true)
        assertTrue(result.consumed)
        assertEquals(0.35f, result.newLinear!!, 1e-4f)
    }

    @Test
    fun atTheEndsTheKeyIsStillTakenButNothingChanges() {
        val up = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = 1f, canZoom = true)
        assertTrue(up.consumed)
        assertNull(up.newLinear)
        val down = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, cameraInFront = true, currentLinear = 0f, canZoom = true)
        assertTrue(down.consumed)
        assertNull(down.newLinear)
    }

    @Test
    fun keysChangeTheVolumeWhenTheCameraIsNotInFront() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val result = VolumeKeyZoom.onKeyDown(key, cameraInFront = false, currentLinear = 0.4f, canZoom = true)
            assertFalse(result.consumed)
            assertNull(result.newLinear)
            assertFalse(VolumeKeyZoom.consumesKeyUp(key, cameraInFront = false, currentLinear = 0.4f, canZoom = true))
        }
    }

    @Test
    fun keysChangeTheVolumeBeforeTheCameraReportsZoom() {
        val result = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = null, canZoom = false)
        assertFalse(result.consumed)
    }

    @Test
    fun keysChangeTheVolumeWhenTheCameraCannotZoom() {
        val result = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = 0f, canZoom = false)
        assertFalse(result.consumed)
        assertFalse(VolumeKeyZoom.consumesKeyUp(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = 0f, canZoom = false))
    }

    @Test
    fun otherKeysAreNeverTaken() {
        for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_ENTER)) {
            assertEquals(0, VolumeKeyZoom.stepsFor(key))
            assertFalse(VolumeKeyZoom.onKeyDown(key, cameraInFront = true, currentLinear = 0.4f, canZoom = true).consumed)
            assertFalse(VolumeKeyZoom.consumesKeyUp(key, cameraInFront = true, currentLinear = 0.4f, canZoom = true))
        }
    }

    @Test
    fun keyUpIsTakenWhenKeyDownIs() {
        assertTrue(VolumeKeyZoom.consumesKeyUp(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = 1f, canZoom = true))
        assertTrue(VolumeKeyZoom.consumesKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, cameraInFront = true, currentLinear = 0.5f, canZoom = true))
    }

    @Test
    fun heldKeyRepeatsWalkTheWholeRange() {
        var linear = 0f
        var presses = 0
        while (true) {
            val r = VolumeKeyZoom.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, cameraInFront = true, currentLinear = linear, canZoom = true)
            assertTrue(r.consumed)
            linear = r.newLinear ?: break
            presses++
        }
        assertEquals(20, presses)
        assertEquals(1f, linear, 1e-4f)
    }
}
