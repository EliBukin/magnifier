package bukin.magnifier

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
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
import kotlin.math.pow

/**
 * The panel over a white page, the magnifier's main use: the ring, its ticks, icons and words
 * must stay readable whatever the camera shows under the panel.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelReadabilityTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MainActivity.cameraStartEnabled = false
    }

    @After
    fun tearDown() {
        MainActivity.cameraStartEnabled = true
    }

    /** Relative luminance (WCAG) of an opaque colour. */
    private fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** [top] (with its alpha) drawn over the opaque [under]. */
    private fun over(top: Int, under: Int): Int {
        val a = Color.alpha(top) / 255.0
        fun mix(t: Int, u: Int) = (t * a + u * (1 - a)).toInt()
        return Color.rgb(mix(Color.red(top), Color.red(under)), mix(Color.green(top), Color.green(under)), mix(Color.blue(top), Color.blue(under)))
    }

    @Test
    fun overAWhitePageEveryPartOfThePanelHasContrast() {
        val scrim = ContextCompat.getColor(app, R.color.controls_scrim)
        for (page in listOf(Color.WHITE, Color.rgb(255, 255, 160), Color.BLACK)) {
            val panel = over(scrim, page)
            val name = Integer.toHexString(page)
            // Parts of a control (the track, ticks, end icons, the buttons' outline): 3:1 at least.
            assertTrue("track on $name: ${contrast(LensRingView.TRACK_COLOR, panel)}", contrast(LensRingView.TRACK_COLOR, panel) >= 3.0)
            assertTrue("ticks on $name", contrast(over(LensRingView.TICK_COLOR, panel), panel) >= 3.0)
            assertTrue("end icons on $name", contrast(over(LensRingView.ICON_COLOR, panel), panel) >= 3.0)
            assertTrue("button outline on $name", contrast(ContextCompat.getColor(app, R.color.control_foreground), panel) >= 3.0)
            // Words and the value: 7:1 (the strictest level), for low vision.
            assertTrue("white words on $name", contrast(Color.WHITE, panel) >= 7.0)
            assertTrue("yellow value on $name", contrast(ContextCompat.getColor(app, R.color.accent), panel) >= 7.0)
            // The yellow part against the grey track: told apart.
            assertTrue("yellow vs track", contrast(ContextCompat.getColor(app, R.color.accent), LensRingView.TRACK_COLOR) >= 2.0)
        }
        // A white page under the panel shows as nearly black.
        val white = over(scrim, Color.WHITE)
        assertTrue("white under the panel: ${Integer.toHexString(white)}", Color.red(white) <= 20)
    }

    @Test
    fun theRenderedPanelOverAWhitePictureIsDarkWithTheRingOnIt() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // A white page fills the screen under the panel. Frozen, so the ring is in use (live it waits
        // for a camera Robolectric doesn't have). Robolectric doesn't draw the frozen bitmap itself
        // (its canvas has no bitmap size limit), so the frozen view's own background is the page.
        val white = Bitmap.createBitmap(72, 156, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        activity.freezeForTest(white)
        activity.findViewById<View>(R.id.frozenView).setBackgroundColor(Color.WHITE)
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
        val shot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(shot))
        File("build/outputs/label-shots").apply { mkdirs() }.let { dir ->
            File(dir, "panel-over-white.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val controls = activity.findViewById<View>(R.id.controls)
        val loc = IntArray(2).also { controls.getLocationInWindow(it) }
        // Above the panel: the white picture. Inside it, in a corner beside the ring: nearly black.
        assertEquals(Color.WHITE, shot.getPixel(root.width / 2, loc[1] - 20))
        val corner = shot.getPixel(8, loc[1] + 8)
        assertTrue("panel corner ${Integer.toHexString(corner)}", Color.red(corner) <= 20)
        // The track at the ring's middle (the frozen ring starts at its left end, so it's grey there).
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        val ringLoc = IntArray(2).also { ring.getLocationInWindow(it) }
        val g = ring.geometry!!
        val track = shot.getPixel(ringLoc[0] + g.cx.toInt(), ringLoc[1] + (g.cy - g.radius).toInt())
        assertTrue("track ${Integer.toHexString(track)} on ${Integer.toHexString(corner)}: ${contrast(track, corner)}", contrast(track, corner) >= 3.0)
    }
}
