package bukin.magnifier

import androidx.annotation.StringRes

/** Which screen to show for the camera permission. */
enum class PermissionScreen(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val button: Int,
) {
    /** Granted: the camera shows. */
    CAMERA(0, 0, 0),

    /** Never asked yet: a short explanation, then "Continue" asks. */
    EXPLAIN(R.string.permission_explain_title, R.string.permission_explain_body, R.string.permission_continue),

    /** Denied, and Android will still ask again: "Grant" asks. */
    DENIED(R.string.permission_denied_title, R.string.permission_denied_body, R.string.permission_grant),

    /**
     * Denied for good ("Don't ask again", or denied twice on Android 11+): Android won't show its
     * dialog any more, so the button opens the app's settings instead.
     */
    BLOCKED(R.string.permission_denied_title, R.string.permission_blocked_body, R.string.permission_open_settings);

    companion object {
        /**
         * [askedBefore]: the app has asked at least once (remembered by the app, because Android
         * gives the same "no rationale" answer before the first ask and after a final denial).
         * [showRationale]: Activity.shouldShowRequestPermissionRationale for the camera.
         */
        fun from(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): PermissionScreen = when {
            granted -> CAMERA
            !askedBefore -> EXPLAIN
            showRationale -> DENIED
            else -> BLOCKED
        }
    }
}
