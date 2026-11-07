package bukin.magnifier

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
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
    fun theControlsAreMirrored() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Show the focus tab too, as on a phone with manual focus.
        activity.manualFocus = ManualFocus(minimumFocusDiopters = 10f, calibrated = true)
        activity.onFocusEvent(FocusEvent.CAMERA_RESTARTED)
        relayout(activity)

        fun v(id: Int) = activity.findViewById<View>(id)
        // Start is on the right: the zoom tab, "zoom out" and the colours there; "zoom in" and the
        // focus button on the left.
        assertTrue(v(R.id.tabFocus).left < v(R.id.tabBrightness).left && v(R.id.tabBrightness).left < v(R.id.tabZoom).left)
        assertTrue(v(R.id.zoomIn).left < v(R.id.zoomOut).left)
        assertTrue(v(R.id.focusButton).left < v(R.id.freezeButton).left)
        assertTrue(v(R.id.freezeButton).left < v(R.id.lightButton).left && v(R.id.lightButton).left < v(R.id.colourButton).left)
        assertEquals("פנס", activity.findViewById<TextView>(R.id.lightButton).text.toString())
        assertEquals("אוטומטי", activity.findViewById<TextView>(R.id.focusButton).text.toString())
        assertEquals("הקפאה", activity.findViewById<TextView>(R.id.freezeButton).text.toString())
        assertEquals("רגיל", activity.findViewById<TextView>(R.id.colourButton).text.toString())
        assertEquals("בהירות", activity.findViewById<TextView>(R.id.tabBrightness).text.toString())
        assertEquals("מיקוד", v(R.id.focusButton).contentDescription)
        assertEquals("צבעים", v(R.id.colourButton).contentDescription)
        assertEquals("הגדלה", v(R.id.zoomSlider).contentDescription)
        screenshot(activity.window.decorView, "he-live.png")

        // The focus tab's row: "near" on the right.
        v(R.id.tabFocus).performClick()
        relayout(activity)
        assertTrue(v(R.id.focusFar).left < v(R.id.focusNear).left)
        screenshot(activity.window.decorView, "he-focus.png")

        // The brightness row: "darker" on the right.
        v(R.id.tabBrightness).performClick()
        relayout(activity)
        assertTrue(v(R.id.brightnessUp).left < v(R.id.brightnessDown).left)
        assertEquals("כהה יותר", v(R.id.brightnessDown).contentDescription)
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
        assertEquals("חי", activity.findViewById<TextView>(R.id.freezeButton).text.toString())
        assertEquals("שמירה", activity.findViewById<TextView>(R.id.saveButton).text.toString())
        assertEquals("שיתוף התמונה כולה", v(R.id.shareButton).contentDescription)
        assertEquals("צהוב", activity.findViewById<TextView>(R.id.colourButton).text.toString())
        // The zoom number reads left to right.
        assertEquals("1.00×", activity.findViewById<TextView>(R.id.tabZoom).text.toString())
        screenshot(activity.window.decorView, "he-frozen.png")
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
