package bukin.magnifier

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Hebrew and right-to-left, without changing any phone's language: the strings, the mirrored
 * layout, and screenshots in build/outputs/rtl-shots/ for a human to look at.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "iw-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HebrewLayoutTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MainActivity.cameraStartEnabled = false
    }

    @After
    fun tearDown() {
        MainActivity.cameraStartEnabled = true
    }

    @Test
    fun thePermissionScreenIsInHebrewAndRightToLeft() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.window.decorView.layoutDirection)
        assertEquals("גישה למצלמה", activity.findViewById<TextView>(R.id.permissionTitle).text.toString())
        assertEquals("המשך", activity.findViewById<TextView>(R.id.permissionButton).text.toString())
        screenshot(activity.window.decorView, "he-permission.png")
    }

    @Test
    fun theControlsAndTheRingAreMirrored() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // As on a phone with manual focus.
        activity.manualFocus = ManualFocus(minimumFocusDiopters = 10f, calibrated = true)
        activity.onFocusEvent(FocusEvent.CAMERA_RESTARTED)
        relayout(activity)

        fun v(id: Int) = activity.findViewById<View>(id)
        // Start is on the right: brightness and the light there, the focus and the colours on the left.
        assertTrue(v(R.id.colourButton).left < v(R.id.focusButton).left && v(R.id.focusButton).left < v(R.id.freezeButton).left)
        assertTrue(v(R.id.freezeButton).left < v(R.id.lightButton).left && v(R.id.lightButton).left < v(R.id.brightnessButton).left)
        // The ring runs from right to left, like the sliders did: zoom out, darker and near on the right.
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        val g = ring.geometry!!
        assertTrue(g.rtl)
        assertTrue(g.xFor(0f) > g.xFor(1f))
        assertEquals("פנס", activity.findViewById<TextView>(R.id.lightButton).text.toString())
        assertEquals("אוטומטי", activity.findViewById<TextView>(R.id.focusButton).text.toString())
        assertEquals("הקפאה", activity.findViewById<TextView>(R.id.freezeButton).text.toString())
        assertEquals("רגיל", activity.findViewById<TextView>(R.id.colourButton).text.toString())
        assertEquals("מיקוד", v(R.id.focusButton).contentDescription)
        assertEquals("צבעים", v(R.id.colourButton).contentDescription)
        assertEquals("בהירות", v(R.id.brightnessButton).contentDescription)
        assertEquals("הגדלה", v(R.id.lensRing).contentDescription)
        assertEquals("הגדלה", activity.findViewById<TextView>(R.id.ringCaption).text.toString())
        screenshot(activity.window.decorView, "he-live.png")

        // The focus ring: the distance in Hebrew, right to left.
        v(R.id.focusButton).performClick()
        activity.onRingTurned(50f)
        relayout(activity)
        assertEquals("מיקוד", activity.findViewById<TextView>(R.id.ringCaption).text.toString())
        assertEquals("20 ס״מ", activity.findViewById<TextView>(R.id.ringValue).text.toString())
        assertEquals(View.TEXT_DIRECTION_LOCALE, v(R.id.ringValue).textDirection)
        assertEquals("מרחק המיקוד", v(R.id.lensRing).contentDescription)
        assertEquals("הצגת ההגדלה בטבעת", clickLabel(v(R.id.ringCentre)))
        screenshot(activity.window.decorView, "he-focus.png")

        // The brightness ring: the level reads left to right ("+2", not "2+").
        v(R.id.brightnessButton).performClick()
        assertEquals(RingMode.BRIGHTNESS, activity.ringMode)
        assertEquals("בהירות", activity.findViewById<TextView>(R.id.ringCaption).text.toString())
        assertEquals(View.TEXT_DIRECTION_LTR, v(R.id.ringValue).textDirection)
        assertEquals(View.TEXT_DIRECTION_LTR, v(R.id.brightnessButton).textDirection)
        relayout(activity)
        screenshot(activity.window.decorView, "he-brightness.png")
    }

    @Test
    fun theFrozenControlsAreMirroredToo() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val picture = Bitmap.createBitmap(72, 156, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8899AA.toInt()) }
        activity.setColourMode(ColorMode.YELLOW_ON_BLACK, announce = false)
        activity.freezeForTest(picture)
        relayout(activity)
        fun v(id: Int) = activity.findViewById<View>(id)
        assertTrue(v(R.id.shareButton).left < v(R.id.freezeButton).left && v(R.id.freezeButton).left < v(R.id.saveButton).left)
        assertEquals("מצלמה", activity.findViewById<TextView>(R.id.freezeButton).text.toString())
        assertEquals("שמירה", activity.findViewById<TextView>(R.id.saveButton).text.toString())
        assertEquals("שיתוף התמונה כולה", v(R.id.shareButton).contentDescription)
        assertEquals("צהוב", activity.findViewById<TextView>(R.id.colourButton).text.toString())
        // The zoom number reads left to right.
        assertEquals("1.00×", activity.findViewById<TextView>(R.id.ringValue).text.toString())
        assertEquals(View.TEXT_DIRECTION_LTR, v(R.id.ringValue).textDirection)
        assertEquals("הגדלה פי 1.00", v(R.id.ringCentre).contentDescription)
        // The frozen ring is mirrored too: its start (the picture as frozen) on the right.
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        assertEquals(0f, ring.fraction, 0f)
        assertTrue(ring.geometry!!.xFor(ring.fraction) > ring.geometry!!.cx)
        screenshot(activity.window.decorView, "he-frozen.png")
    }

    private fun clickLabel(view: View): String? {
        val info = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo())
        return info.actionList.firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }?.label?.toString()
    }

    private fun relayout(activity: MainActivity) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    private fun screenshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/outputs/rtl-shots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
