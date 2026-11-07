package bukin.magnifier

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * How the camera focuses, and what the focus button shows for it.
 *
 * - [AUTO]: continuous autofocus, the camera refocuses by itself. Every launch starts here.
 * - [LOCKED]: a tap on the picture focused (and metered) there once, and the lens stays put
 *   until the next tap or until the user goes back to Auto.
 * - [MANUAL]: the focus ring sets the lens distance itself (only on phones that support it).
 */
enum class FocusMode(
    /** The button's text, also spoken as its state after its label ("Focus"). */
    @param:StringRes val text: Int,
    /** What TalkBack says a double tap on the button will do. */
    @param:StringRes val buttonAction: Int,
    @param:DrawableRes val icon: Int,
    /** Drawn in the accent colour, like the light when it is on: something is not the default. */
    val highlighted: Boolean,
) {
    AUTO(R.string.focus_auto, R.string.focus_action_lock, R.drawable.ic_focus_auto, highlighted = false),
    LOCKED(R.string.focus_locked, R.string.focus_action_auto, R.drawable.ic_focus_locked, highlighted = true),
    MANUAL(R.string.focus_manual, R.string.focus_action_auto, R.drawable.ic_focus_manual, highlighted = true);

    /** The mode after [event]. [manualSupported]: the phone has the focus ring. */
    fun next(event: FocusEvent, manualSupported: Boolean): FocusMode = when (event) {
        FocusEvent.TAP_PICTURE -> LOCKED
        // A toggle: back to Auto, or from Auto lock the focus on the middle of the picture.
        FocusEvent.FOCUS_BUTTON -> if (this == AUTO) LOCKED else AUTO
        FocusEvent.FOCUS_SLIDER -> if (manualSupported) MANUAL else this
        FocusEvent.LEFT_APP, FocusEvent.CAMERA_RESTARTED, FocusEvent.MANUAL_IGNORED -> AUTO
    }
}

/** Everything that changes the focus mode. */
enum class FocusEvent {
    /** A tap on the picture (or a TalkBack click on it, which focuses the middle). */
    TAP_PICTURE,

    /** The focus button. */
    FOCUS_BUTTON,

    /** The focus ring: turned, tapped, or adjusted by TalkBack. */
    FOCUS_SLIDER,

    /** The app left the screen (Home, another app, screen off). */
    LEFT_APP,

    /** The camera was bound afresh ("Try again"). */
    CAMERA_RESTARTED,

    /** The camera turned out to ignore manual focus (see [ManualFocusGuard]): the focus ring goes. */
    MANUAL_IGNORED,
}
