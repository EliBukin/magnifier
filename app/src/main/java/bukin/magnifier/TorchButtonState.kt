package bukin.magnifier

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.camera.core.TorchState

/**
 * What the light button shows. It always follows the camera: [hasFlashUnit] and the camera's
 * reported torch state, never what the button last asked for.
 */
enum class TorchButtonState(
    val enabled: Boolean,
    /** Whether tapping the button should turn the light on (true) or off (false). */
    val tapTurnsOn: Boolean,
    @param:StringRes val text: Int,
    @param:StringRes val contentDescription: Int,
    /** Spoken after the label ("On" / "Off"), or 0 for none. */
    @param:StringRes val stateDescription: Int,
    @param:DrawableRes val icon: Int,
) {
    /** No camera yet: the button is there but can't be used. */
    WAITING(false, true, R.string.light_button, R.string.light_desc, 0, R.drawable.ic_light_off),

    /** This camera has no flash unit, so there is no light to turn on. */
    NO_FLASH(false, true, R.string.light_none, R.string.light_none_desc, 0, R.drawable.ic_light_off),

    OFF(true, true, R.string.light_button, R.string.light_desc, R.string.state_off, R.drawable.ic_light_off),

    ON(true, false, R.string.light_button, R.string.light_desc, R.string.state_on, R.drawable.ic_light_on);

    val isOn: Boolean get() = this == ON

    companion object {
        /**
         * [hasFlashUnit]: null while no camera is bound. [torchState]: CameraX's
         * [TorchState] value, or null before the camera reports one.
         */
        fun from(hasFlashUnit: Boolean?, torchState: Int?): TorchButtonState = when {
            hasFlashUnit == null -> WAITING
            !hasFlashUnit -> NO_FLASH
            torchState == TorchState.ON -> ON
            else -> OFF
        }
    }
}
