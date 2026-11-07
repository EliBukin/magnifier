package bukin.magnifier

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Zoom on the frozen picture, and turning the camera's photo into it. */
class FrozenMathTest {

    private val eps = 1e-3f

    @Test
    fun theSliderStartsAtTheFrozenViewAndIsLogarithmicToEight() {
        // Slider 0 is the picture as it was frozen, like live where the left end is the widest view.
        assertEquals(1f, FrozenZoom.sliderToScale(0f), eps)
        assertEquals(0f, FrozenZoom.scaleToSlider(1f), eps)
        assertEquals(8f, FrozenZoom.sliderToScale(100f), eps)
        assertEquals(2f, FrozenZoom.sliderToScale(100f / 3f), eps)
        for (v in 0..100 step 5) {
            assertEquals(v.toFloat(), FrozenZoom.scaleToSlider(FrozenZoom.sliderToScale(v.toFloat())), 1e-2f)
        }
    }

    @Test
    fun everyStepZoomsByTheSameFactor() {
        val factor = 8f.pow(0.05f) // about 1.11
        var scale = 1f
        repeat(10) {
            val next = FrozenZoom.step(scale, 1)
            assertEquals(factor, next / scale, 1e-3f)
            scale = next
        }
        assertEquals(scale / factor, FrozenZoom.step(scale, -1), 1e-3f)
    }

    @Test
    fun stepsStopAtTheLimits() {
        assertEquals(8f, FrozenZoom.step(7.9f, 3), eps)
        // Never wider than the frozen view.
        assertEquals(1f, FrozenZoom.step(1f, -1), eps)
        assertEquals(1f, FrozenZoom.step(1.05f, -3), eps)
        // From between grid points, the first step lands on the next one.
        assertEquals(FrozenZoom.sliderToScale(5f), FrozenZoom.step(1.05f, 1), eps)
    }

    @Test
    fun doubleTapDoublesAndAtTheTopGoesBack() {
        assertEquals(2f, FrozenZoom.doubleTapTarget(1f), eps)
        assertEquals(8f, FrozenZoom.doubleTapTarget(5f), eps)
        assertEquals(1f, FrozenZoom.doubleTapTarget(8f), eps)
    }

    @Test
    fun highZoomPhotosKeepFewerPixels() {
        assertEquals(1, PhotoDecode.sampleSize(1f))
        assertEquals(1, PhotoDecode.sampleSize(2.9f))
        assertEquals(2, PhotoDecode.sampleSize(3f))
        assertEquals(2, PhotoDecode.sampleSize(4f)) // the A20's maximum
        assertEquals(4, PhotoDecode.sampleSize(6f))
        assertEquals(8, PhotoDecode.sampleSize(12f))
        assertEquals(16, PhotoDecode.sampleSize(30f))
    }

    @Test
    fun exifOrientationBecomesDegrees() {
        assertEquals(0, PhotoDecode.exifDegrees(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(90, PhotoDecode.exifDegrees(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, PhotoDecode.exifDegrees(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, PhotoDecode.exifDegrees(ExifInterface.ORIENTATION_ROTATE_270))
        assertNull(PhotoDecode.exifDegrees(ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(PhotoDecode.exifDegrees(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
    }

    @Test
    fun theJpegsOwnExifWinsAndCameraXIsTheFallback() {
        // The A20: CameraX says 90, EXIF says 6 (rotate 90).
        assertEquals(90, PhotoDecode.uprightRotation(90, 90))
        // A camera that turned the pixels itself writes EXIF "normal".
        assertEquals(0, PhotoDecode.uprightRotation(0, 90))
        assertEquals(270, PhotoDecode.uprightRotation(null, 270))
        assertEquals(90, PhotoDecode.uprightRotation(null, -270))
    }

    @Test
    fun rotationsMoveTheCornersClockwise() {
        // A 4 x 2 picture. Turned 90 degrees clockwise it is 2 x 4, and its top-left corner is
        // now top right.
        val r90 = PhotoDecode.rotation(4f, 2f, 90)
        assertEquals(2f to 0f, r90.mapX(0f, 0f) to r90.mapY(0f, 0f))
        assertEquals(2f to 4f, r90.mapX(4f, 0f) to r90.mapY(4f, 0f))
        val r180 = PhotoDecode.rotation(4f, 2f, 180)
        assertEquals(4f to 2f, r180.mapX(0f, 0f) to r180.mapY(0f, 0f))
        val r270 = PhotoDecode.rotation(4f, 2f, 270)
        assertEquals(0f to 4f, r270.mapX(0f, 0f) to r270.mapY(0f, 0f))
        assertEquals(0f to 0f, r270.mapX(4f, 0f) to r270.mapY(4f, 0f))
        assertEquals(2 to 4, PhotoDecode.uprightSize(4, 2, 90))
        assertEquals(4 to 2, PhotoDecode.uprightSize(4, 2, 180))
        for (degrees in listOf(0, 90, 180, 270)) {
            val (w, h) = PhotoDecode.uprightSize(4, 2, degrees)
            assertEquals(Box(0f, 0f, w.toFloat(), h.toFloat()), PhotoDecode.rotation(4f, 2f, degrees).mapBox(4f, 2f))
        }
    }

    @Test
    fun thePartTheScreenShowedFillsTheScreenAgain() {
        // As logged on the A20: a 4128 x 3096 photo, turned 90, the screen's part (0, 595)-(4128, 2501).
        val crop = Box(0f, 595f, 4128f, 2501f)
        val t = PhotoDecode.baseTransform(4128f, 3096f, 90, crop, 720f, 1560f)
        // Its corners land on the screen's corners (turned: the photo's bottom-left is the screen's top-left).
        assertEquals(0f, t.mapX(0f, 2501f), 0.5f)
        assertEquals(0f, t.mapY(0f, 2501f), 0.5f)
        assertEquals(720f, t.mapX(4128f, 595f), 0.5f)
        assertEquals(1560f, t.mapY(4128f, 595f), 0.5f)
        // The whole photo is wider than the screen, the same height.
        val whole = t.mapBox(4128f, 3096f)
        assertEquals(1560f, whole.height, 1f)
        assertTrue(whole.width > 1100f && whole.left < 0f && whole.right > 720f)
        // Half the pixels (a sampled decode), same result.
        val half = PhotoDecode.baseTransform(2064f, 1548f, 90, Box(0f, 297.5f, 2064f, 1250.5f), 720f, 1560f)
        assertEquals(0f, half.mapX(0f, 1250.5f), 0.5f)
        assertEquals(1560f, half.mapY(2064f, 297.5f), 0.5f)
    }

    @Test
    fun theScreensOwnPictureIsShownAsItIs() {
        val t = PhotoDecode.baseTransform(720f, 1560f, 0, Box(0f, 0f, 720f, 1560f), 720f, 1560f)
        assertEquals(Affine(1f, 0f, 0f, 0f, 1f, 0f), t)
    }
}
