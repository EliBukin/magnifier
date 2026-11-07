package bukin.magnifier

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/** The ring as a control: turning, tapping, TalkBack's slider actions, right to left. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-port-xhdpi")
class LensRingViewTest {

    private class Recorder : LensRingView.Listener {
        val values = mutableListOf<Float>()
        val steps = mutableListOf<Int>()
        var starts = 0
        var ends = 0
        override fun onRingTouchStart() {
            starts++
        }
        override fun onRingValue(value: Float) {
            values += value
        }
        override fun onRingTouchEnd() {
            ends++
        }
        override fun onRingStep(steps: Int) {
            this.steps += steps
        }
    }

    private val density = 2f // xhdpi

    /** A ring 348dp wide (a 360dp phone minus the panel's padding), laid out. */
    private fun ring(rtl: Boolean = false): Pair<LensRingView, Recorder> {
        val view = LensRingView(ApplicationProvider.getApplicationContext())
        view.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val width = (348 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.configure(0f, 100f, 0f, 0f, 50f, R.drawable.ic_zoom_out, R.drawable.ic_zoom_in)
        val recorder = Recorder()
        view.listener = recorder
        return view to recorder
    }

    private fun LensRingView.point(angle: Float, rOffset: Float = 0f): Pair<Float, Float> {
        val g = geometry!!
        val rad = Math.toRadians(angle.toDouble())
        val r = g.radius + rOffset
        return (g.cx + r * sin(rad)).toFloat() to (g.cy - r * cos(rad)).toFloat()
    }

    private fun LensRingView.touch(action: Int, at: Pair<Float, Float>): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, at.first, at.second, 0)
        return onTouchEvent(event).also { event.recycle() }
    }

    /** A finger down at [from] degrees, along the arc in small moves, up at [to]. */
    private fun LensRingView.drag(from: Float, to: Float) {
        assertTrue(touch(MotionEvent.ACTION_DOWN, point(from)))
        val n = 20
        for (i in 1..n) touch(MotionEvent.ACTION_MOVE, point(from + (to - from) * i / n))
        touch(MotionEvent.ACTION_UP, point(to))
    }

    private fun LensRingView.tap(angle: Float, rOffset: Float = 0f) {
        touch(MotionEvent.ACTION_DOWN, point(angle, rOffset))
        touch(MotionEvent.ACTION_UP, point(angle, rOffset))
    }

    @Test
    fun theRingIsSizedFromItsWidthAndItsBandIsThickEnough() {
        val (view, _) = ring()
        val g = view.geometry!!
        // 56dp of band, more than the 48dp asked for.
        assertEquals(56f, 2 * g.bandHalf / density, 1e-3f)
        assertTrue(view.measuredHeight / density in 150f..190f)
        assertEquals(view.measuredHeight.toFloat(), RingGeometry.heightFor(g, 16 * density, 4 * density), 1f)
    }

    @Test
    fun turningTheRingMovesTheValueSmoothlyWithTheFinger() {
        val (view, rec) = ring()
        // From the thumb (the middle) 30 degrees to the right: a fifth of the 150-degree ring.
        view.drag(0f, 30f)
        assertEquals(70f, view.value, 0.5f)
        assertEquals(70f, rec.values.last(), 0.5f)
        // Many small changes on the way, not one jump.
        assertTrue("${rec.values.size} changes", rec.values.size >= 10)
        assertEquals(1, rec.starts)
        assertEquals(1, rec.ends)
        assertTrue(rec.steps.isEmpty())
        // Grabbing it anywhere turns it from where it is, like a lens ring: no jump to the finger.
        view.drag(-60f, -75f)
        assertEquals(60f, view.value, 0.5f)
        // It stops at the ends.
        view.drag(-70f, 80f)
        assertEquals(100f, view.value, 0f)
    }

    @Test
    fun rightToLeftTheSameTurnGoesTheOtherWay() {
        val (view, _) = ring(rtl = true)
        assertTrue(view.geometry!!.rtl)
        // Its start is on the right.
        assertTrue(view.geometry!!.xFor(0f) > view.geometry!!.xFor(1f))
        view.drag(0f, 30f)
        assertEquals(30f, view.value, 0.5f)
        view.drag(0f, -15f)
        assertEquals(40f, view.value, 0.5f)
    }

    @Test
    fun aStepGridSnapsTheTurn() {
        val (view, rec) = ring()
        view.configure(-10f, 10f, 1f, 0f, 0f, R.drawable.ic_brightness_down, R.drawable.ic_brightness_up)
        view.drag(0f, 22f)
        // 22 / 150 of 20 levels = 2.9 -> 3.
        assertEquals(3f, view.value, 0f)
        assertTrue(rec.values.all { it == Math.round(it).toFloat() })
        assertEquals(rec.values, rec.values.distinct())
    }

    @Test
    fun tapsBesideTheThumbAndOnTheEndIconsStep() {
        val (view, rec) = ring()
        view.tap(-40f)
        view.tap(40f)
        view.tap(1f)
        assertEquals(listOf(-1, 1), rec.steps)
        assertEquals(50f, view.value, 0f)
        // The end icons sit inside the ring, off its band; a tap on one steps towards that end.
        val g = view.geometry!!
        val iconR = g.radius - g.bandHalf - 8 * density
        view.tap(-69f, iconR - g.radius)
        view.tap(69f, iconR - g.radius)
        assertEquals(listOf(-1, 1, -1, 1), rec.steps)
        assertEquals(LensRingView.Hit.START_ICON, view.hit(view.point(-69f, iconR - g.radius).first, view.point(-69f, iconR - g.radius).second))
        // Mirrored in Hebrew: the left part and the left icon are towards the end.
        val (rtlView, rtlRec) = ring(rtl = true)
        rtlView.tap(-40f)
        rtlView.tap(-69f, iconR - g.radius)
        assertEquals(listOf(1, 1), rtlRec.steps)
    }

    @Test
    fun touchesAwayFromTheBandAreLeftForTheViewsBelow() {
        val (view, rec) = ring()
        val g = view.geometry!!
        // The middle of the ring, where the value and Freeze are.
        assertFalse(view.touch(MotionEvent.ACTION_DOWN, g.cx to g.cy - 60 * density))
        // Just outside the band.
        assertFalse(view.touch(MotionEvent.ACTION_DOWN, view.point(0f, -30 * density)))
        assertTrue(rec.steps.isEmpty() && rec.values.isEmpty())
    }

    @Test
    fun talkBackSeesASliderWithItsRangeAndActions() {
        val (view, rec) = ring()
        val info = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo())
        assertEquals("android.widget.SeekBar", info.className)
        assertEquals(0f, info.rangeInfo!!.min, 0f)
        assertEquals(100f, info.rangeInfo!!.max, 0f)
        assertEquals(50f, info.rangeInfo!!.current, 0f)
        val ids = info.actionList.map { it.id }
        assertTrue(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids)
        assertTrue(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids)
        assertTrue(android.R.id.accessibilityActionSetProgress in ids)
        assertFalse(AccessibilityNodeInfo.ACTION_CLICK in ids)

        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(listOf(1, -1), rec.steps)
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 80f) }
        assertTrue(view.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        assertEquals(80f, view.value, 0f)
        assertEquals(listOf(80f), rec.values)

        // At the end: no "forward". Disabled: nothing to do.
        view.configure(0f, 100f, 0f, 0f, 100f, R.drawable.ic_zoom_out, R.drawable.ic_zoom_in)
        val atEnd = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo()).actionList.map { it.id }
        assertFalse(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in atEnd)
        view.isEnabled = false
        val disabled = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo()).actionList.map { it.id }
        assertFalse(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in disabled)
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
    }
}
