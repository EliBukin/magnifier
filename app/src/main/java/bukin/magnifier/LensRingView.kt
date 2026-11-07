package bukin.magnifier

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The lens ring: a big arc at the top of the panel that adjusts one value, like turning the focus
 * or zoom ring of a camera lens. A grey track with tick marks, a yellow part from [fillFrom] to a
 * white thumb at the value.
 *
 * - **Turn it:** drag anywhere along the band (at least 56dp thick). The value follows the finger's
 *   angle, the whole arc being the whole range, so it moves smoothly from wherever it is.
 * - **Tap it:** a tap on the band beside the thumb, or on the icon at either end, is one step
 *   that way (like the old − / + buttons). The activity takes the step ([Listener.onRingStep]).
 * - **TalkBack:** a slider ("seek control"): its value is the state description the activity
 *   sets, swipe up / down steps, and "set progress" sets a value. The arrow keys step too.
 *
 * In a right-to-left language the ring runs from right to left, like the sliders did.
 */
class LensRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** A finger started turning the ring (not called for taps). */
        fun onRingTouchStart() = Unit

        /** The user turned the ring to [value] (already on the step grid, if there is one). */
        fun onRingValue(value: Float)

        /** The finger left the ring. */
        fun onRingTouchEnd() = Unit

        /** A tap beside the thumb or on an end, an arrow key or TalkBack: one step (-1 or +1). */
        fun onRingStep(steps: Int)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val bandHalf = BAND_HALF_DP * density
    private val thumbRadius = THUMB_RADIUS_DP * density
    private val trackWidth = TRACK_DP * density
    private val maxRadius = MAX_RADIUS_DP * density
    private val iconSize = (ICON_DP * density).roundToInt()
    private val iconHitRadius = ICON_HIT_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    @VisibleForTesting
    internal var geometry: RingGeometry? = null
        private set

    var valueFrom = 0f
        private set
    var valueTo = 100f
        private set

    /** The step grid for values (0: none). */
    var stepSize = 0f
        private set

    /** Where the yellow part starts: the start for zoom and focus, 0 for brightness. */
    var fillFrom = 0f
        private set

    /** The value the thumb shows. Set by the app; a drag sets it too. */
    var value = 0f
        set(v) {
            val clamped = v.coerceIn(minOf(valueFrom, valueTo), maxOf(valueFrom, valueTo))
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** True from the moment a drag starts until the finger leaves. */
    var isDragging = false
        private set

    private var startIcon: Drawable? = null
    private var endIcon: Drawable? = null
    private var startIconId = 0
    private var endIconId = 0

    // Touch tracking.
    private var touching = false
    private var downX = 0f
    private var downY = 0f
    private var lastAngle = 0f
    private var dragFraction = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TRACK_COLOR
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TICK_COLOR
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = THUMB_OUTLINE_COLOR
    }
    private val oval = RectF()

    init {
        isFocusable = true
        trackPaint.strokeWidth = trackWidth
        fillPaint.strokeWidth = trackWidth
        tickPaint.strokeWidth = TICK_WIDTH_DP * density
        thumbOutline.strokeWidth = 2f * density
    }

    /** What the ring adjusts now: its range, step grid, where the yellow starts, and the end icons. */
    fun configure(from: Float, to: Float, step: Float, fill: Float, value: Float, @DrawableRes start: Int, @DrawableRes end: Int) {
        if (from != valueFrom || to != valueTo || step != stepSize || fill != fillFrom) {
            valueFrom = from
            valueTo = to
            stepSize = step
            fillFrom = fill
            invalidate()
        }
        if (start != startIconId) {
            startIconId = start
            startIcon = icon(start)
            invalidate()
        }
        if (end != endIconId) {
            endIconId = end
            endIcon = icon(end)
            invalidate()
        }
        this.value = value
    }

    private fun icon(@DrawableRes id: Int): Drawable? =
        if (id == 0) null else ContextCompat.getDrawable(context, id)?.mutate()?.apply { setTint(ICON_COLOR) }

    /** The thumb's place along the ring, 0..1. */
    val fraction: Float
        get() = if (valueTo == valueFrom) 0f else ((value - valueFrom) / (valueTo - valueFrom)).coerceIn(0f, 1f)

    private fun valueAt(fraction: Float): Float {
        val raw = valueFrom + fraction.coerceIn(0f, 1f) * (valueTo - valueFrom)
        return snap(raw)
    }

    private fun snap(raw: Float): Float {
        if (stepSize <= 0f) return raw
        val snapped = valueFrom + ((raw - valueFrom) / stepSize).roundToInt() * stepSize
        return snapped.coerceIn(minOf(valueFrom, valueTo), maxOf(valueFrom, valueTo))
    }

    // ---- Size ----------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val ring = fitRing(width.toFloat())
        val wanted = RingGeometry.heightFor(ring, thumbRadius, END_GAP_DP * density).roundToInt()
        setMeasuredDimension(width, resolveSize(wanted, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometry = fitRing(w.toFloat())
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (width > 0) geometry = fitRing(width.toFloat())
        invalidate()
    }

    private fun fitRing(width: Float) = RingGeometry.fit(
        width = width,
        bandHalf = bandHalf,
        thumbRadius = thumbRadius,
        sideMargin = SIDE_MARGIN_DP * density,
        maxRadius = maxRadius,
        rtl = layoutDirection == LAYOUT_DIRECTION_RTL,
    )

    // ---- Drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ring = geometry ?: return
        val dim = if (isEnabled) 255 else DISABLED_ALPHA
        trackPaint.alpha = dim
        fillPaint.alpha = dim
        val tickAlpha = (TICK_COLOR ushr 24) and 0xFF
        tickPaint.alpha = if (isEnabled) tickAlpha else tickAlpha / 2
        val r = ring.radius
        oval.set(ring.cx - r, ring.cy - r, ring.cx + r, ring.cy + r)
        // Android's arcs start at 3 o'clock; the ring's angles start straight up.
        canvas.drawArc(oval, -90f - ring.half, ring.sweep, false, trackPaint)
        val fillFraction = if (valueTo == valueFrom) 0f else ((fillFrom - valueFrom) / (valueTo - valueFrom)).coerceIn(0f, 1f)
        val a0 = ring.angleFor(fillFraction)
        val a1 = ring.angleFor(fraction)
        if (abs(a1 - a0) > 0.01f) canvas.drawArc(oval, -90f + minOf(a0, a1), abs(a1 - a0), false, fillPaint)
        drawTicks(canvas, ring)
        drawIcon(canvas, ring, startIcon, 0f)
        drawIcon(canvas, ring, endIcon, 1f)
        val tx = ring.xFor(fraction)
        val ty = ring.yFor(fraction)
        thumbPaint.alpha = dim
        canvas.drawCircle(tx, ty, thumbRadius, thumbPaint)
        canvas.drawCircle(tx, ty, thumbRadius, thumbOutline)
    }

    /** Ticks on the inside of the track: one per step (or 20 in all), longer every fifth. */
    private fun drawTicks(canvas: Canvas, ring: RingGeometry) {
        val count = tickCount()
        val outer = ring.radius - trackWidth / 2f - TICK_GAP_DP * density
        for (i in 0..count) {
            val f = i / count.toFloat()
            val long = i % 5 == 0 || i == count
            val inner = outer - (if (long) TICK_LONG_DP else TICK_SHORT_DP) * density
            canvas.drawLine(ring.xFor(f, outer), ring.yFor(f, outer), ring.xFor(f, inner), ring.yFor(f, inner), tickPaint)
        }
    }

    private fun tickCount(): Int {
        if (stepSize > 0f) {
            val steps = (abs(valueTo - valueFrom) / stepSize).roundToInt()
            if (steps in 2..MAX_TICKS) return steps
        }
        return DEFAULT_TICKS
    }

    private fun drawIcon(canvas: Canvas, ring: RingGeometry, icon: Drawable?, end: Float) {
        icon ?: return
        val (x, y) = iconCentre(ring, end)
        val half = iconSize / 2
        icon.setBounds(x.roundToInt() - half, y.roundToInt() - half, x.roundToInt() + half, y.roundToInt() + half)
        icon.alpha = if (isEnabled) 255 else DISABLED_ALPHA
        icon.draw(canvas)
    }

    /** Where the start (0) or end (1) icon sits: just inside the ring, near that end. */
    private fun iconCentre(ring: RingGeometry, end: Float): Pair<Float, Float> {
        val r = ring.radius - bandHalf - ICON_INSET_DP * density
        val towardsMiddle = ICON_ANGLE_IN / ring.sweep
        val f = if (end == 0f) towardsMiddle else 1f - towardsMiddle
        return ring.xFor(f, r) to ring.yFor(f, r)
    }

    // ---- Touch ---------------------------------------------------------------------------------

    /** What a touch at (x, y) lands on: the band, an end's icon (-1 start, +1 end), or nothing. */
    @VisibleForTesting
    internal fun hit(x: Float, y: Float): Hit {
        val ring = geometry ?: return Hit.NONE
        for ((end, hit) in listOf(0f to Hit.START_ICON, 1f to Hit.END_ICON)) {
            if ((if (end == 0f) startIcon else endIcon) == null) continue
            val (ix, iy) = iconCentre(ring, end)
            if (hypot(x - ix, y - iy) <= iconHitRadius) return hit
        }
        return if (ring.inBand(x, y)) Hit.BAND else Hit.NONE
    }

    enum class Hit { NONE, BAND, START_ICON, END_ICON }

    private var downHit = Hit.NONE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val ring = geometry ?: return false
        if (!isEnabled) return hit(event.x, event.y) != Hit.NONE
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downHit = hit(event.x, event.y)
                // Anywhere else (the value and Freeze inside the ring) is for the views below.
                if (downHit == Hit.NONE) return false
                touching = true
                isDragging = false
                downX = event.x
                downY = event.y
                lastAngle = ring.angleAt(event.x, event.y)
                dragFraction = fraction
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return false
                if (!isDragging && downHit == Hit.BAND && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    listener?.onRingTouchStart()
                }
                if (isDragging) turnTo(ring, event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touching) return false
                val wasDragging = isDragging
                if (wasDragging) {
                    turnTo(ring, event.x, event.y)
                } else {
                    val steps = when (downHit) {
                        Hit.START_ICON -> -1
                        Hit.END_ICON -> 1
                        else -> ring.tapSteps(event.x, event.y, fraction, thumbRadius)
                    }
                    if (steps != 0) listener?.onRingStep(steps)
                    performClick()
                }
                endTouch(wasDragging)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!touching) return false
                endTouch(isDragging)
                return true
            }
        }
        return touching
    }

    /** The finger is at (x, y): the ring turns by the angle it moved since the last event. */
    private fun turnTo(ring: RingGeometry, x: Float, y: Float) {
        // Too near the middle, the angle is meaningless.
        if (ring.distanceAt(x, y) < ring.radius * MIN_TURN_RADIUS) return
        val angle = ring.angleAt(x, y)
        dragFraction = (dragFraction + ring.fractionDelta(lastAngle, angle)).coerceIn(0f, 1f)
        lastAngle = angle
        val newValue = valueAt(dragFraction)
        if (newValue != value) {
            value = newValue
            listener?.onRingValue(newValue)
        }
    }

    private fun endTouch(wasDragging: Boolean) {
        touching = false
        isDragging = false
        isPressed = false
        downHit = Hit.NONE
        if (wasDragging) listener?.onRingTouchEnd()
    }

    // A tap is a click for accessibility services; the step itself is taken in onTouchEvent.
    override fun performClick(): Boolean = super.performClick()

    // ---- Keys ----------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val steps = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
            KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
            else -> 0
        }
        if (steps == 0) return super.onKeyDown(keyCode, event)
        listener?.onRingStep(steps)
        return true
    }

    // ---- TalkBack: a slider ----------------------------------------------------------------------

    override fun getAccessibilityClassName(): CharSequence = SEEK_BAR

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val node = AccessibilityNodeInfoCompat.wrap(info)
        node.className = SEEK_BAR
        node.rangeInfo = RangeInfoCompat.obtain(
            RangeInfoCompat.RANGE_TYPE_FLOAT,
            minOf(valueFrom, valueTo),
            maxOf(valueFrom, valueTo),
            value,
        )
        // Clicking it does nothing on its own: TalkBack users adjust it.
        node.removeAction(AccessibilityActionCompat.ACTION_CLICK)
        node.isClickable = false
        if (isEnabled) {
            if (value < maxOf(valueFrom, valueTo)) {
                node.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
            }
            if (value > minOf(valueFrom, valueTo)) {
                node.addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            }
            node.addAction(AccessibilityActionCompat.ACTION_SET_PROGRESS)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                    listener?.onRingStep(1)
                    return true
                }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    listener?.onRingStep(-1)
                    return true
                }
                android.R.id.accessibilityActionSetProgress -> {
                    val wanted = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                    val newValue = snap(wanted.coerceIn(minOf(valueFrom, valueTo), maxOf(valueFrom, valueTo)))
                    value = newValue
                    listener?.onRingValue(newValue)
                    return true
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    companion object {
        private const val SEEK_BAR = "android.widget.SeekBar"

        /** The touch band: 28dp on each side of the track's line, 56dp in all. */
        const val BAND_HALF_DP = 28f
        const val THUMB_RADIUS_DP = 16f
        private const val TRACK_DP = 12f
        private const val TICK_WIDTH_DP = 2f
        private const val TICK_GAP_DP = 3f
        private const val TICK_SHORT_DP = 5f
        private const val TICK_LONG_DP = 10f
        private const val SIDE_MARGIN_DP = 2f
        private const val END_GAP_DP = 4f
        private const val MAX_RADIUS_DP = 220f
        private const val ICON_DP = 22f

        /** An end icon's tap target: 24dp around it, 48dp across. */
        private const val ICON_HIT_DP = 24f
        private const val ICON_INSET_DP = 8f

        /** The end icons sit this many degrees in from the ends. */
        private const val ICON_ANGLE_IN = 6f

        /** A drag nearer the centre than this part of the radius doesn't turn the ring. */
        private const val MIN_TURN_RADIUS = 0.25f
        private const val DEFAULT_TICKS = 20
        private const val MAX_TICKS = 40
        private const val DISABLED_ALPHA = 100
        private const val TRACK_COLOR = 0xFF5A5A5A.toInt()
        private const val TICK_COLOR = 0xB3FFFFFF.toInt()
        private const val ICON_COLOR = 0xE6FFFFFF.toInt()
        private const val THUMB_OUTLINE_COLOR = 0x99000000.toInt()
    }
}
