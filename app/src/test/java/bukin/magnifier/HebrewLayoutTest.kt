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
        // Show the focus row too, as on a phone with manual focus.
        activity.manualFocus = ManualFocus(minimumFocusDiopters = 10f, calibrated = true)
        activity.onFocusEvent(FocusEvent.CAMERA_RESTARTED)
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)

        val label = activity.findViewById<View>(R.id.zoomLabel)
        val focus = activity.findViewById<View>(R.id.focusButton)
        val light = activity.findViewById<View>(R.id.lightButton)
        val zoomOut = activity.findViewById<View>(R.id.zoomOut)
        val zoomIn = activity.findViewById<View>(R.id.zoomIn)
        val near = activity.findViewById<View>(R.id.focusNear)
        val far = activity.findViewById<View>(R.id.focusFar)
        // Start is on the right: the zoom value, "zoom out" and "near" there; the light, "zoom in"
        // and "far" on the left.
        assertTrue(light.left < focus.left && focus.left < label.left)
        assertTrue(zoomIn.left < zoomOut.left)
        assertTrue(far.left < near.left)
        assertEquals("פנס", activity.findViewById<TextView>(R.id.lightButton).text.toString())
        assertEquals("אוטומטי", activity.findViewById<TextView>(R.id.focusButton).text.toString())
        assertEquals("מיקוד", focus.contentDescription)
        assertEquals("הגדלה", activity.findViewById<View>(R.id.zoomSlider).contentDescription)
        assertEquals("מרחק המיקוד", activity.findViewById<View>(R.id.focusSlider).contentDescription)
        // The zoom value still fits on a 360dp phone.
        assertTrue(label.width / activity.resources.displayMetrics.density >= 64f)
        screenshot(activity.window.decorView, "he-controls.png")
    }

    private fun screenshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/outputs/rtl-shots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
