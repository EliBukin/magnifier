package bukin.magnifier

import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProblemTest {

    @Test
    fun cameraStateErrorsMapToMessages() {
        assertEquals(CameraProblem.IN_USE, CameraProblem.fromCameraStateError(CameraState.ERROR_CAMERA_IN_USE))
        assertEquals(CameraProblem.IN_USE, CameraProblem.fromCameraStateError(CameraState.ERROR_MAX_CAMERAS_IN_USE))
        assertEquals(CameraProblem.DISABLED, CameraProblem.fromCameraStateError(CameraState.ERROR_CAMERA_DISABLED))
        assertEquals(CameraProblem.DO_NOT_DISTURB, CameraProblem.fromCameraStateError(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED))
        assertEquals(CameraProblem.OTHER, CameraProblem.fromCameraStateError(CameraState.ERROR_CAMERA_FATAL_ERROR))
        assertEquals(CameraProblem.OTHER, CameraProblem.fromCameraStateError(CameraState.ERROR_STREAM_CONFIG))
        assertEquals(CameraProblem.OTHER, CameraProblem.fromCameraStateError(12345))
    }

    @Test
    fun onlyAMissingRearCameraCannotBeRetried() {
        assertFalse(CameraProblem.NO_REAR_CAMERA.canRetry)
        for (problem in CameraProblem.values().filter { it != CameraProblem.NO_REAR_CAMERA }) {
            assertTrue(problem.name, problem.canRetry)
        }
    }
}
