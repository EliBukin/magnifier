package bukin.magnifier

import android.hardware.camera2.CameraMetadata

/**
 * Waits for continuous autofocus to settle before a tap locks the focus, when the tap comes
 * right after manual focus.
 *
 * Why: a tap runs the camera's one-shot autofocus scan from wherever the lens is. After a near
 * manual setting, the A20's scan sometimes fails from there (2 of 4 tries in step 1). So the
 * app first hands the lens back to continuous autofocus, waits until it reports a finished scan
 * (`PASSIVE_FOCUSED` or `PASSIVE_UNFOCUSED`), and only then locks.
 *
 * Ready when, after at least [minWaitMs]:
 * - the frames are back in a continuous AF mode, and
 * - they report a finished scan, having been seen scanning first, or having waited
 *   [quietMs] (a camera already in focus may never report a scan).
 * Or after [timeoutMs], whatever the frames say: the lock then goes ahead as before.
 *
 * Thread-safe: frames arrive on a camera thread, the timeout on the main thread; exactly one
 * of them gets `true`.
 */
class AfSettle(
    private val startMs: Long,
    private val minWaitMs: Long = MIN_WAIT_MS,
    private val quietMs: Long = QUIET_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private var done = false
    private var sawScan = false

    /** Why it finished, for the log. */
    @get:Synchronized
    var outcome: String = "waiting"
        private set

    /** One frame's AF mode and state at [now]. True exactly once: lock now. */
    @Synchronized
    fun onFrame(afMode: Int?, afState: Int?, now: Long): Boolean {
        if (done) return false
        val continuous = afMode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
            afMode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        if (!continuous) return false
        if (afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN) sawScan = true
        val finished = afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
        val elapsed = now - startMs
        if (finished && elapsed >= minWaitMs && (sawScan || elapsed >= quietMs)) {
            done = true
            outcome = "settled after $elapsed ms (state $afState, scan seen: $sawScan)"
            return true
        }
        return false
    }

    /** The main thread's check at [now]. True exactly once, if no frame finished it first. */
    @Synchronized
    fun onTimeout(now: Long): Boolean {
        if (done || now - startMs < timeoutMs) return false
        done = true
        outcome = "timed out after ${now - startMs} ms (scan seen: $sawScan)"
        return true
    }

    /** Stops waiting: a newer focus change replaced this one. */
    @Synchronized
    fun cancel() {
        done = true
        outcome = "cancelled"
    }

    companion object {
        const val MIN_WAIT_MS = 100L
        const val QUIET_MS = 400L
        const val TIMEOUT_MS = 2000L
    }
}
