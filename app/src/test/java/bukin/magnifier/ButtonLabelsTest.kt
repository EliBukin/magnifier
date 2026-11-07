package bukin.magnifier

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
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
import kotlin.math.sqrt

/**
 * Every word the round buttons can show, in English and Hebrew, at each size the panel uses
 * (under 360dp, 360-400dp, 400dp and up), laid out with real fonts:
 * - the side buttons' word is under the circle, on one line, at 10sp or more, and within the
 *   button's width;
 * - Freeze's word is inside its circle.
 * Each test also draws a sheet of the words for a human to look at (build/outputs/label-shots).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonLabelsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MainActivity.cameraStartEnabled = false
    }

    @After
    fun tearDown() {
        MainActivity.cameraStartEnabled = true
    }

    private val sideWords = listOf(
        R.string.colour_short_normal, R.string.colour_short_high_contrast, R.string.colour_short_inverted,
        R.string.colour_short_yellow_on_black, R.string.focus_auto, R.string.focus_locked, R.string.focus_manual,
        R.string.light_button, R.string.light_none, R.string.save_button, R.string.share_button,
    )

    @Test @Config(qualifiers = "en-w320dp-h640dp-port-hdpi")
    fun englishUnder360dp() = check("en-320")

    @Test @Config(qualifiers = "en-w360dp-h780dp-port-xhdpi")
    fun english360dp() = check("en-360")

    @Test @Config(qualifiers = "en-w411dp-h891dp-port-xhdpi")
    fun english411dpLikeTheA20() = check("en-411")

    @Test @Config(qualifiers = "iw-w320dp-h640dp-port-hdpi")
    fun hebrewUnder360dp() = check("he-320")

    @Test @Config(qualifiers = "iw-w360dp-h780dp-port-xhdpi")
    fun hebrew360dp() = check("he-360")

    @Test @Config(qualifiers = "iw-w411dp-h891dp-port-xhdpi")
    fun hebrew411dpLikeTheA20() = check("he-411")

    private fun check(name: String) {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val res = activity.resources
        val density = res.displayMetrics.density
        val scaled = res.displayMetrics.scaledDensity
        val colour = activity.findViewById<MaterialButton>(R.id.colourButton)
        // The levels go on the brightness button, which keeps numbers left to right ("+10" in Hebrew too).
        val brightness = activity.findViewById<MaterialButton>(R.id.brightnessButton)
        val freeze = activity.findViewById<MaterialButton>(R.id.freezeButton)
        val circle = res.getDimension(R.dimen.side_button_height) - res.getDimension(R.dimen.side_label_height)
        val words = sideWords.map { colour to res.getString(it) } + listOf("+10", "−10", "0").map { brightness to it }
        val tiles = mutableListOf<Bitmap>()

        for ((side, word) in words) {
            side.text = word
            relayout(activity)
            val layout = side.layout
            val what = "$name \"$word\""
            assertEquals("$what on one line", 1, layout.lineCount)
            assertEquals("$what not cut", 0, layout.getEllipsisCount(0))
            assertTrue("$what at ${side.textSize / scaled}sp", side.textSize / scaled >= 10f - 0.01f)
            val room = side.width - side.totalPaddingLeft - side.totalPaddingRight
            assertTrue("$what is ${layout.getLineWidth(0) / density}dp wide, room ${room / density}dp", layout.getLineWidth(0) <= room)
            // Under the circle, inside the button (the touch target).
            val top = side.totalPaddingTop
            assertTrue("$what starts at ${top / density}dp, the circle ends at ${circle / density}dp", top >= circle - 0.5f * density)
            assertTrue("$what ends inside the button", top + layout.height <= side.height + 0.5f * density)
            tiles += snapshot(side)
        }

        for (word in listOf(res.getString(R.string.freeze_button), res.getString(R.string.live_button))) {
            freeze.text = word
            relayout(activity)
            val layout = freeze.layout
            val what = "$name Freeze \"$word\""
            assertEquals("$what on one line", 1, layout.lineCount)
            assertEquals("$what not cut", 0, layout.getEllipsisCount(0))
            // Inside the circle at the word's baseline, clear of the edge by 4dp.
            val r = freeze.width / 2f
            val baseline = freeze.totalPaddingTop + layout.getLineBaseline(0)
            val dy = baseline - freeze.height / 2f
            val halfChord = sqrt(r * r - dy * dy)
            assertTrue("$what is ${layout.getLineWidth(0) / density}dp, chord ${2 * halfChord / density}dp", layout.getLineWidth(0) / 2 <= halfChord - 4 * density)
            tiles += snapshot(freeze)
        }
        saveSheet(tiles, "labels-$name.png")
    }

    private fun relayout(activity: MainActivity) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    /** The button as drawn, on the panel's black. */
    private fun snapshot(view: View): Bitmap {
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        view.draw(c)
        return b
    }

    private fun saveSheet(tiles: List<Bitmap>, file: String) {
        val gap = 8
        val w = tiles.sumOf { it.width + gap } + gap
        val h = tiles.maxOf { it.height } + 2 * gap
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.BLACK)
        var x = gap.toFloat()
        for (t in tiles) {
            c.drawBitmap(t, x, (h - t.height).toFloat() - gap, null)
            x += t.width + gap
        }
        val dir = File("build/outputs/label-shots").apply { mkdirs() }
        File(dir, file).outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
