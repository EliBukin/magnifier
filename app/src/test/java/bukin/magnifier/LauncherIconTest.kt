package bukin.magnifier

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The launcher icon (G "Glow ring", made by scripts/make-icon.py): the manifest uses it, it is
 * adaptive with a monochrome layer for themed icons, its pixels are where they should be, and the
 * legacy PNGs, Play's 512 px icon and the site's favicon are there at the right sizes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theManifestUsesTheMipmapIcons() {
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test
    @Config(sdk = [33])
    fun theIconIsAdaptiveWithAMonochromeLayer() {
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = ContextCompat.getDrawable(app, id)
            assertTrue("${app.resources.getResourceEntryName(id)} is adaptive", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            assertNotNull(icon.background)
            assertNotNull(icon.foreground)
            assertNotNull("themed icons need the monochrome layer", icon.monochrome)
        }
    }

    @Test
    fun theLayersDrawTheGlowRing() {
        val size = 108 // one pixel per unit of the 108 x 108 canvas
        fun draw(id: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            ContextCompat.getDrawable(app, id)!!.apply { setBounds(0, 0, size, size) }.draw(Canvas(b))
            return b
        }
        val bg = draw(R.drawable.ic_launcher_background)
        val fg = draw(R.drawable.ic_launcher_foreground)
        val mono = draw(R.drawable.ic_launcher_monochrome)
        fun yellow(c: Int) = Color.red(c) > 220 && Color.green(c) > 180 && Color.blue(c) < 120 && Color.alpha(c) > 240
        // The glass's centre is (49, 49), its ring 15.5 wide 8; the handle runs to (72, 72).
        assertTrue("ring", yellow(fg.getPixel(49, 49 - 16)))
        assertTrue("handle", yellow(fg.getPixel(68, 68)))
        assertEquals("the lens is black", Color.BLACK, fg.getPixel(49, 49))
        // The background: black corners, a yellowish glow just outside the ring, black past it.
        assertEquals(Color.BLACK, bg.getPixel(2, 2))
        val glow = bg.getPixel(49, 49 - 22)
        assertTrue("glow ${Integer.toHexString(glow)}", Color.red(glow) > 60 && Color.blue(glow) < Color.red(glow) / 2)
        assertEquals(Color.BLACK, bg.getPixel(49, 49 - 31))
        // Monochrome: the ring and the handle, nothing in the lens.
        assertTrue(Color.alpha(mono.getPixel(49, 49 - 16)) > 240)
        assertTrue(Color.alpha(mono.getPixel(68, 68)) > 240)
        assertEquals(0, Color.alpha(mono.getPixel(49, 49)))
    }

    /** Width, height and colour type (6 = RGBA) from a PNG's IHDR chunk. */
    private class Png(path: String) {
        private val head = File(path).inputStream().use { it.readNBytes(26) }
        private fun int(at: Int) = (0 until 4).fold(0) { v, i -> (v shl 8) or (head[at + i].toInt() and 0xFF) }
        val width = int(16)
        val height = int(20)
        val hasAlpha = head[25].toInt() == 6
    }

    @Test
    fun legacyPngsPlayIconAndFaviconHaveTheirSizes() {
        val densities = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        for ((density, px) in densities) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val image = Png("src/main/res/mipmap-$density/$name.png")
                assertEquals("$density/$name", px, image.width)
                assertEquals("$density/$name", px, image.height)
            }
        }
        val play = Png("../docs/play/assets/icon-512.png")
        assertEquals(512, play.width)
        assertEquals(512, play.height)
        assertTrue("Play wants a 32-bit PNG", play.hasAlpha)
        assertEquals(96, Png("../docs/site/favicon.png").width)
        assertEquals("G", File("../docs/play/assets/icon.txt").readText().trim())
    }
}
