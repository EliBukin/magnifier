package bukin.magnifier

import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_EDOF
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_MACRO
import android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF
import android.hardware.camera2.CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE
import android.hardware.camera2.CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
import android.hardware.camera2.CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualFocusTest {

    private val allAf = intArrayOf(
        CONTROL_AF_MODE_OFF, CONTROL_AF_MODE_AUTO, CONTROL_AF_MODE_MACRO,
        CONTROL_AF_MODE_CONTINUOUS_VIDEO, CONTROL_AF_MODE_CONTINUOUS_PICTURE,
    )
    private val ten = ManualFocus(minimumFocusDiopters = 10f, calibrated = true)

    // ---- The support check ----

    @Test
    fun theA20HasTheSlider() {
        // Read from its camera service: AF modes [0 1 3 4], minimum focus 10, CALIBRATED. Its
        // request key list leaves android.lens.focusDistance out, but the lens obeys it, so the
        // list isn't checked (ManualFocusGuard catches a camera that really ignores it).
        val a20 = ManualFocus.check(
            afModes = intArrayOf(CONTROL_AF_MODE_OFF, CONTROL_AF_MODE_AUTO, CONTROL_AF_MODE_CONTINUOUS_VIDEO, CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            minimumFocusDistance = 10f,
            calibration = LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
        )
        assertEquals(ManualFocus(10f, calibrated = true), a20)
    }

    @Test
    fun theEmulatorHasTheSlider() {
        // Android 36 emulator back camera: AF modes [0 1 2 3 4], minimum 20 (5 cm), APPROXIMATE.
        val emulator = ManualFocus.check(allAf, 20f, LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE)
        assertEquals(ManualFocus(20f, calibrated = true), emulator)
    }

    @Test
    fun fixedFocusCamerasHaveNoSlider() {
        // Minimum focus distance 0 (fixed focus), missing, or nonsense.
        assertNull(ManualFocus.check(allAf, 0f, null))
        assertNull(ManualFocus.check(allAf, null, null))
        assertNull(ManualFocus.check(allAf, -1f, null))
        assertNull(ManualFocus.check(allAf, Float.NaN, null))
        assertNull(ManualFocus.check(allAf, Float.POSITIVE_INFINITY, null))
        // A typical fixed-focus camera: only OFF, and 0.
        assertNull(ManualFocus.check(intArrayOf(CONTROL_AF_MODE_OFF), 0f, null))
    }

    @Test
    fun camerasWithoutAfModeOffHaveNoSlider() {
        assertNull(ManualFocus.check(allAf.filter { it != CONTROL_AF_MODE_OFF }.toIntArray(), 10f, null))
        assertNull(ManualFocus.check(intArrayOf(CONTROL_AF_MODE_EDOF), 10f, null))
        assertNull(ManualFocus.check(intArrayOf(), 10f, null))
        assertNull(ManualFocus.check(null, 10f, null))
        assertNotNull(ManualFocus.check(allAf, 10f, null))
    }

    @Test
    fun onlyApproximateOrCalibratedDistancesCount() {
        assertEquals(true, ManualFocus.check(allAf, 10f, LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED)?.calibrated)
        assertEquals(true, ManualFocus.check(allAf, 10f, LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE)?.calibrated)
        assertEquals(false, ManualFocus.check(allAf, 10f, LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED)?.calibrated)
        assertEquals(false, ManualFocus.check(allAf, 10f, null)?.calibrated)
    }

    // ---- The slider: near (0) to far (100) ----

    @Test
    fun theSliderRunsFromNearestToInfinity() {
        assertEquals(10f, ten.sliderToDiopters(0f), 1e-6f)
        assertEquals(5f, ten.sliderToDiopters(50f), 1e-6f)
        assertEquals(1f, ten.sliderToDiopters(90f), 1e-5f)
        assertEquals(0f, ten.sliderToDiopters(100f), 1e-6f)
        // Out of range values are clamped.
        assertEquals(10f, ten.sliderToDiopters(-5f), 1e-6f)
        assertEquals(0f, ten.sliderToDiopters(120f), 1e-6f)
    }

    @Test
    fun lensDistancesMapBackToTheSlider() {
        for (value in listOf(0f, 12.5f, 50f, 99f, 100f)) {
            assertEquals(value, ten.dioptersToSlider(ten.sliderToDiopters(value)), 1e-4f)
        }
        // A lens reporting beyond its own range, or nothing, stays on the slider.
        assertEquals(0f, ten.dioptersToSlider(14f), 1e-6f)
        assertEquals(100f, ten.dioptersToSlider(-1f), 1e-6f)
        assertEquals(100f, ten.dioptersToSlider(Float.NaN), 1e-6f)
    }

    @Test
    fun theNearAndFarButtonsStepATwentieth() {
        assertEquals(55f, ManualFocus.step(50f, 1), 1e-6f)
        assertEquals(45f, ManualFocus.step(50f, -1), 1e-6f)
        assertEquals(100f, ManualFocus.step(98f, 1), 1e-6f)
        assertEquals(0f, ManualFocus.step(3f, -1), 1e-6f)
        var value = 0f
        repeat(20) { value = ManualFocus.step(value, 1) }
        assertEquals(100f, value, 1e-4f)
    }

    @Test
    fun talkBackHearsADistance() {
        assertEquals(ManualFocus.Distance.Centimetres(10), ten.describe(0f))
        assertEquals(ManualFocus.Distance.Centimetres(20), ten.describe(50f))
        assertEquals(ManualFocus.Distance.Metres(1f), ten.describe(90f))
        assertEquals(ManualFocus.Distance.Metres(2f), ten.describe(95f))
        assertEquals(ManualFocus.Distance.Far, ten.describe(100f))
        // An uncalibrated lens has no real distances: how far along instead.
        val uncalibrated = ten.copy(calibrated = false)
        assertEquals(ManualFocus.Distance.Percent(0), uncalibrated.describe(0f))
        assertEquals(ManualFocus.Distance.Percent(35), uncalibrated.describe(35f))
        assertEquals(ManualFocus.Distance.Far, uncalibrated.describe(100f))
    }
}
