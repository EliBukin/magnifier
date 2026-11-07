package bukin.magnifier

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A short ring where the user tapped to focus: it shrinks into place, turns yellow when the
 * camera reports focus, and fades out. Purely visual; hidden from TalkBack.
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val radius = 36f * density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    // A dark outline under the ring keeps it visible on white paper.
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = 0x99000000.toInt()
    }
    private var centerX = 0f
    private var centerY = 0f
    private var shown = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    /** Shows the ring at ([x], [y]) in this view's coordinates. */
    fun show(x: Float, y: Float) {
        centerX = x
        centerY = y
        shown = true
        ring.color = Color.WHITE
        shrink?.cancel()
        animate().cancel()
        alpha = 1f
        shrink = ValueAnimator.ofFloat(1.3f, 1f).apply {
            duration = SHRINK_MS
            addUpdateListener { scaleRing(it.animatedValue as Float) }
            start()
        }
        animate().setStartDelay(SHRINK_MS + HOLD_MS).setDuration(FADE_MS).alpha(0f)
            .withEndAction { shown = false }
    }

    private var shrink: ValueAnimator? = null

    /** Colours the ring by the camera's focus result. */
    fun showResult(focused: Boolean) {
        if (!shown) return
        ring.color = if (focused) FOCUSED_COLOR else Color.WHITE
        invalidate()
    }

    private var currentScale = 1f

    private fun scaleRing(scale: Float) {
        currentScale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!shown) return
        val r = radius * currentScale
        canvas.drawCircle(centerX, centerY, r, outline)
        canvas.drawCircle(centerX, centerY, r, ring)
    }

    private companion object {
        const val SHRINK_MS = 200L
        const val HOLD_MS = 700L
        const val FADE_MS = 300L
        const val FOCUSED_COLOR = 0xFFFFD54F.toInt()
    }
}
