package bukin.magnifier

import android.view.KeyEvent

/**
 * The volume keys zoom while the camera screen is in front: up zooms in, down zooms out, one
 * [ZoomMath.STEP] per press (and per key repeat while held).
 */
object VolumeKeyZoom {

    /** What to do with one key press. */
    data class Result(
        /** True when the app takes the key, so the phone's volume doesn't change. */
        val consumed: Boolean,
        /** The linear zoom to set, or null to leave the zoom as it is. */
        val newLinear: Float?,
    )

    private val NOT_OURS = Result(consumed = false, newLinear = null)

    /** +1 for volume up, -1 for volume down, 0 for any other key. */
    fun stepsFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        else -> 0
    }

    /**
     * [cameraInFront]: the live camera is showing, with no permission or error screen over it.
     * [currentLinear]: the camera's linear zoom now, or null when there is no camera (yet).
     * [canZoom]: whether this camera can zoom at all.
     *
     * The keys are taken only while the camera is in front and can zoom; everywhere else they
     * change the volume as usual. At either end of the range they are still taken (and do
     * nothing), so holding a key past the end doesn't suddenly start changing the volume.
     */
    fun onKeyDown(keyCode: Int, cameraInFront: Boolean, currentLinear: Float?, canZoom: Boolean): Result {
        val steps = stepsFor(keyCode)
        if (steps == 0 || !cameraInFront || currentLinear == null || !canZoom) return NOT_OURS
        val target = ZoomMath.stepLinear(currentLinear, steps)
        return Result(consumed = true, newLinear = if (target == currentLinear) null else target)
    }

    /** Key-up for a volume key is taken exactly when its key-down would be. */
    fun consumesKeyUp(keyCode: Int, cameraInFront: Boolean, currentLinear: Float?, canZoom: Boolean): Boolean =
        stepsFor(keyCode) != 0 && cameraInFront && currentLinear != null && canZoom
}
