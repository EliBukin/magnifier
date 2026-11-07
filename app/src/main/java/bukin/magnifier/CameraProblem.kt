package bukin.magnifier

import androidx.annotation.StringRes
import androidx.camera.core.CameraState

/** A reason the camera isn't showing, as the error panel words it. */
enum class CameraProblem(@param:StringRes val message: Int, val canRetry: Boolean) {
    NO_REAR_CAMERA(R.string.error_no_camera, canRetry = false),
    IN_USE(R.string.error_in_use, canRetry = true),
    DISABLED(R.string.error_disabled, canRetry = true),
    DO_NOT_DISTURB(R.string.error_dnd, canRetry = true),
    OTHER(R.string.error_generic, canRetry = true);

    companion object {
        /** The problem for a CameraX [CameraState.StateError] code. */
        fun fromCameraStateError(code: Int): CameraProblem = when (code) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> IN_USE
            CameraState.ERROR_CAMERA_DISABLED -> DISABLED
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> DO_NOT_DISTURB
            else -> OTHER
        }
    }
}
