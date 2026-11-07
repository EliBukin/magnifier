package bukin.magnifier

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.max

/**
 * Checks that the camera really applies manual focus. [ManualFocus.check] trusts the
 * characteristics, which can be wrong, so the first time Manual is used the frames must echo
 * it: `CONTROL_AF_MODE` OFF and a `LENS_FOCUS_DISTANCE` close to one that was asked for, within
 * [timeoutMs]. If they don't (no distance, -1, or another distance), the camera ignores manual
 * focus: the app hides the slider and goes back to Auto.
 *
 * One verdict per camera binding: once [State.CONFIRMED] or [State.FAILED], it stays so until
 * [reset]. Thread-safe: requests come from the main thread, results from a camera thread.
 */
class ManualFocusGuard(
    /** The nearest focus, in diopters: sets the tolerance. */
    minimumFocusDiopters: Float,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    enum class State { IDLE, CHECKING, CONFIRMED, FAILED }

    /** How far a result may be from a requested distance and still count as an echo. */
    val toleranceDiopters: Float = max(MIN_TOLERANCE_DIOPTERS, minimumFocusDiopters * TOLERANCE_FRACTION)

    @get:Synchronized
    var state = State.IDLE
        private set

    /** Why the check failed, for the log. */
    @get:Synchronized
    var failure: String? = null
        private set

    private val requested = ArrayDeque<Float>()
    private var deadline = 0L
    private var lastSeen = "no frame"

    /** Manual focus asked the camera for [diopters] at [now] (ms). The first one starts the check. */
    @Synchronized
    fun onRequest(diopters: Float, now: Long) {
        when (state) {
            State.IDLE -> {
                state = State.CHECKING
                deadline = now + timeoutMs
                requested.clear()
                requested.addLast(diopters)
                lastSeen = "no frame"
            }
            // While the slider is dragged the frames lag the newest request: any recent one counts.
            State.CHECKING -> {
                requested.addLast(diopters)
                if (requested.size > MAX_REMEMBERED) requested.removeFirst()
            }
            State.CONFIRMED, State.FAILED -> Unit
        }
    }

    /** A frame's result: its AF mode and lens focus distance (null when it has none). */
    @Synchronized
    fun onResult(afMode: Int?, diopters: Float?, now: Long): State {
        if (state != State.CHECKING) return state
        lastSeen = "afMode=$afMode focusDistance=$diopters"
        val echoed = afMode == CameraMetadata.CONTROL_AF_MODE_OFF && diopters != null && diopters >= 0f &&
            requested.any { abs(it - diopters) <= toleranceDiopters }
        if (echoed) {
            state = State.CONFIRMED
        } else if (now >= deadline) {
            fail()
        }
        return state
    }

    /** Called when the time is up, in case no frame came at all. */
    @Synchronized
    fun onTimeout(now: Long): State {
        if (state == State.CHECKING && now >= deadline) fail()
        return state
    }

    /**
     * Manual focus ended before a verdict (the user left Manual, or the app left the screen):
     * no verdict, check again next time.
     */
    @Synchronized
    fun cancelCheck() {
        if (state != State.CHECKING) return
        state = State.IDLE
        requested.clear()
    }

    /** A new camera binding: check again on the next manual focus. */
    @Synchronized
    fun reset() {
        state = State.IDLE
        failure = null
        requested.clear()
    }

    private fun fail() {
        state = State.FAILED
        failure = "no echo of manual focus within $timeoutMs ms: asked for ${requested.toList()} diopters " +
            "(±$toleranceDiopters), last frame $lastSeen"
    }

    companion object {
        const val TIMEOUT_MS = 1000L
        const val MIN_TOLERANCE_DIOPTERS = 0.1f
        const val TOLERANCE_FRACTION = 0.03f
        private const val MAX_REMEMBERED = 64
    }
}
