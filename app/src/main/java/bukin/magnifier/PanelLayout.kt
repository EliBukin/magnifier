package bukin.magnifier

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * The control panel's content: a vertical LinearLayout at most [MAX_WIDTH_DP] wide, so on a wide
 * screen the buttons stay beside the ring instead of spreading to the screen's edges.
 */
class PanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val maxWidth = (MAX_WIDTH_DP * resources.displayMetrics.density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > maxWidth) MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    companion object {
        const val MAX_WIDTH_DP = 480f
    }
}

/**
 * The panel's background, the full width of the screen. A touch on its empty parts (between the
 * buttons, inside the ring away from its band) stops here, so it never reaches the camera picture
 * underneath, where it would focus on a spot the user can't see.
 */
class ControlPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }
}
