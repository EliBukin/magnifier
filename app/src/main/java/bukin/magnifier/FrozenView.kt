package bukin.magnifier

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlin.math.abs

/**
 * The frozen picture: pinch to zoom, drag with one finger to move, double tap to zoom in (and,
 * at the most, back). Zoom 1 shows it exactly as the live view did.
 *
 * It first shows a quick copy of the screen (the "placeholder"), then the camera's sharp photo
 * in its place, keeping the user's zoom and position, so nothing jumps.
 */
class FrozenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called with the new scale whenever the zoom changes (by touch or by [zoomTo]). */
    var onScaleChanged: ((Float) -> Unit)? = null

    /** Called when a finger zooms the picture (pinch or double tap), before the zoom changes. */
    var onUserZoom: (() -> Unit)? = null

    var bitmap: Bitmap? = null
        private set

    /** The bitmap's pixels to the view at scale 1. */
    private val base = Matrix()

    /** Where the bitmap lies at scale 1, in view pixels. */
    private val content = RectF()

    var scale = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    var minScale = 1f
        private set
    val maxScale = FrozenZoom.HIGH

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val drawMatrix = Matrix()
    private var animator: ValueAnimator? = null

    /** A smaller copy, for a bitmap too big for this phone's graphics (see [onDraw]). */
    private var fallback: Bitmap? = null

    init {
        isFocusable = true
    }

    /**
     * Shows [newBitmap]: [transform] puts its pixels on the view at scale 1. With [keepView], the
     * zoom and position stay (the sharp photo replacing the placeholder); otherwise the picture
     * starts at scale 1. [lowestScale] is the most the user may zoom out.
     */
    fun show(newBitmap: Bitmap, transform: Affine, lowestScale: Float, keepView: Boolean) {
        animator?.cancel()
        bitmap = newBitmap
        fallback?.recycle()
        fallback = null
        base.setValues(transform.values())
        content.set(0f, 0f, newBitmap.width.toFloat(), newBitmap.height.toFloat())
        base.mapRect(content)
        minScale = lowestScale.coerceAtMost(1f)
        if (!keepView) {
            scale = 1f
            panX = 0f
            panY = 0f
        }
        clampView()
        invalidate()
        onScaleChanged?.invoke(scale)
    }

    /** Forgets the picture. The caller recycles the bitmap, after this view stopped drawing it. */
    fun clear() {
        animator?.cancel()
        bitmap = null
        fallback?.recycle()
        fallback = null
        scale = 1f
        panX = 0f
        panY = 0f
        minScale = 1f
        invalidate()
    }

    /** The colour filter the picture is drawn with (null: as it is). */
    val pictureFilter: ColorFilter? get() = paint.colorFilter

    fun setPictureFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidate()
    }

    /** Zooms to [target] around the view point ([focusX], [focusY]); the middle by default. */
    fun zoomTo(target: Float, focusX: Float = width / 2f, focusY: Float = height / 2f, animate: Boolean = false) {
        animator?.cancel()
        val goal = target.coerceIn(minScale, maxScale)
        if (!animate) {
            applyZoom(goal, focusX, focusY)
            return
        }
        val start = scale
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIMATION_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                applyZoom(start + (goal - start) * t, focusX, focusY)
            }
            start()
        }
    }

    private fun applyZoom(target: Float, focusX: Float, focusY: Float) {
        val newScale = target.coerceIn(minScale, maxScale)
        // Keep the picture point under the focus where it is.
        panX = focusX - newScale * (focusX - panX) / scale
        panY = focusY - newScale * (focusY - panY) / scale
        val changed = newScale != scale
        scale = newScale
        clampView()
        invalidate()
        if (changed) onScaleChanged?.invoke(scale)
    }

    private fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clampView()
        invalidate()
    }

    /** Keeps the picture covering the view where it is big enough, and centred where it isn't. */
    private fun clampView() {
        if (width == 0 || height == 0) return
        panX = clampAxis(panX, content.left, content.right, width.toFloat())
        panY = clampAxis(panY, content.top, content.bottom, height.toFloat())
    }

    private fun clampAxis(pan: Float, start: Float, end: Float, size: Float): Float {
        val low = scale * start
        val high = scale * end
        return if (high - low <= size) {
            (size - (low + high)) / 2f
        } else {
            pan.coerceIn(size - high, -low)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampView()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        if (source.isRecycled) return
        drawMatrix.set(base)
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(panX, panY)
        val tooBig = source.width > canvas.maximumBitmapWidth || source.height > canvas.maximumBitmapHeight
        if (!tooBig) {
            canvas.drawBitmap(source, drawMatrix, paint)
            return
        }
        // Older phones' graphics can't hold a photo this big as one texture: draw a copy that fits.
        val small = fallback ?: makeFallback(source, canvas.maximumBitmapWidth, canvas.maximumBitmapHeight).also { fallback = it }
        drawMatrix.preScale(source.width / small.width.toFloat(), source.height / small.height.toFloat())
        canvas.drawBitmap(small, drawMatrix, paint)
    }

    private fun makeFallback(source: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val k = minOf(maxW / source.width.toFloat(), maxH / source.height.toFloat())
        Log.w(MainActivity.TAG, "frozen photo ${source.width}x${source.height} is over the ${maxW}x$maxH texture limit: drawing at $k")
        return Bitmap.createScaledBitmap(source, (source.width * k).toInt(), (source.height * k).toInt(), true)
    }

    // ---- Touch: pinch, drag, double tap --------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            animator?.cancel()
            onUserZoom?.invoke()
            applyZoom(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            panBy(-distanceX, -distanceY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onUserZoom?.invoke()
            zoomTo(FrozenZoom.doubleTapTarget(scale, minScale, maxScale), e.x, e.y, animate = true)
            return true
        }
    })

    // The frozen picture has no tap action, so there is no click for TalkBack or a keyboard to make.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ---- TalkBack: moving around without dragging ----------------------------------------------

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val compat = AccessibilityNodeInfoCompat.wrap(info)
        if (bitmap == null) return
        for ((action, _) in panActions()) {
            if (canPan(action)) compat.addAction(action)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val pan = panActions().firstOrNull { it.first.id == action }
        if (pan != null && bitmap != null) {
            val (dx, dy) = pan.second
            panBy(dx * width / 2f, dy * height / 2f)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /** Each TalkBack scroll moves half a screen; the direction is where the view looks next. */
    private fun panActions(): List<Pair<AccessibilityActionCompat, Pair<Float, Float>>> = listOf(
        AccessibilityActionCompat.ACTION_SCROLL_LEFT to (1f to 0f),
        AccessibilityActionCompat.ACTION_SCROLL_RIGHT to (-1f to 0f),
        AccessibilityActionCompat.ACTION_SCROLL_UP to (0f to 1f),
        AccessibilityActionCompat.ACTION_SCROLL_DOWN to (0f to -1f),
    )

    private fun canPan(action: AccessibilityActionCompat): Boolean {
        val dir = panActions().first { it.first.id == action.id }.second
        val savedX = panX
        val savedY = panY
        panX += dir.first
        panY += dir.second
        clampView()
        val moved = abs(panX - savedX) > 0.5f || abs(panY - savedY) > 0.5f
        panX = savedX
        panY = savedY
        return moved
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ZOOM_ANIMATION_MS = 200L
    }
}
