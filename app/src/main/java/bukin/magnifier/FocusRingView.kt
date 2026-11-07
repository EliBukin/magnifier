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
 * camera reports focus, and fades out half a second later. Purely visual; hidden from TalkBack.
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

    private var currentTap = 0

    /**
     * Shows the ring at ([x], [y]) in this view's coordinates, until [showResult] (or a timeout).
     * Returns this tap's number, for [showResult].
     */
    fun show(x: Float, y: Float): Int {
        currentTap++
        centerX = x
        centerY = y
        shown = true
        ring.color = Color.WHITE
        shrink?.cancel()
        animate().cancel()
        removeCallbacks(fadeOut)
        alpha = 1f
        shrink = ValueAnimator.ofFloat(1.3f, 1f).apply {
            duration = SHRINK_MS
            addUpdateListener { scaleRing(it.animatedValue as Float) }
            start()
        }
        // Slow cameras may never answer; don't leave the ring up.
        postDelayed(fadeOut, NO_RESULT_TIMEOUT_MS)
        return currentTap
    }

    private var shrink: ValueAnimator? = null

    private val fadeOut = Runnable {
        animate().setDuration(FADE_MS).alpha(0f).withEndAction { shown = false }
    }

    /**
     * Colours the ring by the camera's focus result for tap [tap], holds it briefly, then fades it.
     * A late result for an earlier tap is ignored.
     */
    fun showResult(tap: Int, focused: Boolean) {
        if (!shown || tap != currentTap) return
        ring.color = if (focused) FOCUSED_COLOR else Color.WHITE
        invalidate()
        removeCallbacks(fadeOut)
        postDelayed(fadeOut, HOLD_AFTER_RESULT_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeOut)
        shrink?.cancel()
        super.onDetachedFromWindow()
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
        const val HOLD_AFTER_RESULT_MS = 500L
        const val NO_RESULT_TIMEOUT_MS = 3000L
        const val FADE_MS = 300L
        const val FOCUSED_COLOR = 0xFFFFD54F.toInt()
    }
}
