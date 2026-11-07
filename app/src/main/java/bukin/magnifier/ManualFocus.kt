package bukin.magnifier

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import kotlin.math.roundToInt

/**
 * Manual focus on a camera that supports it: the focus slider runs from the nearest distance the
 * lens can focus at (slider 0, "near") to infinity (slider [SLIDER_MAX], "far").
 *
 * Camera2 focus distances are in diopters (1 / metres): 0 is infinity and
 * [minimumFocusDiopters] is the nearest. The slider is linear in diopters, which spends most of
 * its length on the close range a magnifier is used at (with a 10-diopter lens: 10 cm at 0,
 * 20 cm at the middle, 1 m at 90%).
 */
data class ManualFocus(
    /** `LENS_INFO_MINIMUM_FOCUS_DISTANCE`: the nearest focus, in diopters. Always > 0. */
    val minimumFocusDiopters: Float,
    /**
     * Whether the distances are real diopters (`APPROXIMATE` or `CALIBRATED`). If not
     * (`UNCALIBRATED`), they only order near to far and can't be spoken as centimetres.
     */
    val calibrated: Boolean,
) {

    fun sliderToDiopters(value: Float): Float =
        minimumFocusDiopters * (1f - value.coerceIn(0f, SLIDER_MAX) / SLIDER_MAX)

    fun dioptersToSlider(diopters: Float): Float {
        if (diopters.isNaN()) return SLIDER_MAX
        return (SLIDER_MAX * (1f - diopters / minimumFocusDiopters)).coerceIn(0f, SLIDER_MAX)
    }

    /** What TalkBack says for a slider position (see [Distance]). */
    fun describe(value: Float): Distance {
        val v = value.coerceIn(0f, SLIDER_MAX)
        if (v >= SLIDER_MAX) return Distance.Far
        if (!calibrated) return Distance.Percent((v / SLIDER_MAX * 100f).roundToInt())
        val centimetres = (100f / sliderToDiopters(v)).roundToInt()
        return if (centimetres < 100) {
            Distance.Centimetres(centimetres)
        } else {
            Distance.Metres((centimetres / 10f).roundToInt() / 10f)
        }
    }

    /** A focus distance in words. */
    sealed interface Distance {
        data object Far : Distance
        data class Centimetres(val cm: Int) : Distance
        data class Metres(val m: Float) : Distance

        /** Uncalibrated lens: how far along from near (0) to far (100). */
        data class Percent(val percent: Int) : Distance
    }

    companion object {
        const val SLIDER_MAX = 100f

        /** One near / far button press: 1/20 of the slider, like the zoom buttons. */
        const val STEP = 5f

        /** The slider value [steps] presses from [value] (towards far when positive). */
        fun step(value: Float, steps: Int): Float = (value + steps * STEP).coerceIn(0f, SLIDER_MAX)

        /**
         * Manual focus for a camera with these characteristics, or null when it doesn't
         * officially support it. All three must hold:
         * - `CONTROL_AF_AVAILABLE_MODES` has `CONTROL_AF_MODE_OFF`;
         * - `LENS_INFO_MINIMUM_FOCUS_DISTANCE` > 0 (0 or missing = fixed focus);
         * - `LENS_FOCUS_DISTANCE` is among the capture request keys the camera accepts.
         */
        fun check(
            afModes: IntArray?,
            minimumFocusDistance: Float?,
            acceptsFocusDistance: Boolean,
            calibration: Int?,
        ): ManualFocus? {
            if (afModes == null || CameraMetadata.CONTROL_AF_MODE_OFF !in afModes) return null
            if (minimumFocusDistance == null || !minimumFocusDistance.isFinite() || minimumFocusDistance <= 0f) return null
            if (!acceptsFocusDistance) return null
            val calibrated = calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE ||
                calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
            return ManualFocus(minimumFocusDistance, calibrated)
        }

        /** [check] for a camera's [CameraCharacteristics]. */
        fun from(characteristics: CameraCharacteristics): ManualFocus? = check(
            afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
            minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            acceptsFocusDistance = CaptureRequest.LENS_FOCUS_DISTANCE in characteristics.availableCaptureRequestKeys,
            calibration = characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION),
        )
    }
}
