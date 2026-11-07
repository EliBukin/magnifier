package bukin.magnifier

import android.media.ExifInterface
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Zoom on the frozen picture. The scale is relative to how the picture first shows, which is
 * how the live view looked (1 = no jump). The zoom slider runs on a log scale from [LOW]
 * (slider 0) to [HIGH] (slider 100), so every slider step or button press zooms by the same
 * factor (about 1.15x). [LOW] lets the photo's edges, which were off screen, come into view.
 */
object FrozenZoom {
    const val SLIDER_MAX = 100f
    const val LOW = 0.5f
    const val HIGH = 8f

    fun sliderToScale(value: Float): Float =
        LOW * (HIGH / LOW).pow(value.coerceIn(0f, SLIDER_MAX) / SLIDER_MAX)

    fun scaleToSlider(scale: Float): Float =
        (SLIDER_MAX * ln(scale.coerceIn(LOW, HIGH) / LOW) / ln(HIGH / LOW)).coerceIn(0f, SLIDER_MAX)

    /** The scale [steps] slider steps (1/20 of the slider each) from [scale], within [min]..[max]. */
    fun step(scale: Float, steps: Int, min: Float, max: Float): Float {
        val linear = ZoomMath.stepLinear(scaleToSlider(scale) / SLIDER_MAX, steps)
        return sliderToScale(linear * SLIDER_MAX).coerceIn(min, max)
    }

    /** A double tap zooms in 2x; at the maximum it goes back to the start (1). */
    fun doubleTapTarget(scale: Float, min: Float, max: Float): Float =
        if (scale >= max * 0.99f) 1f.coerceIn(min, max) else (scale * 2f).coerceIn(min, max)

    /** The smallest scale for a picture whose first view covers [contentW] x [contentH] of a view [viewW] x [viewH]: all of it in view, but not below [LOW]. */
    fun minScale(contentW: Float, contentH: Float, viewW: Float, viewH: Float): Float {
        if (contentW <= 0f || contentH <= 0f) return 1f
        val fit = minOf(viewW / contentW, viewH / contentH)
        return fit.coerceIn(LOW, 1f)
    }
}

/**
 * A 2D affine transform, x' = a*x + b*y + tx and y' = c*x + d*y + ty: the same values as an
 * [android.graphics.Matrix] (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y), kept
 * plain so the maths can be unit-tested.
 */
data class Affine(val a: Float, val b: Float, val tx: Float, val c: Float, val d: Float, val ty: Float) {
    fun mapX(x: Float, y: Float) = a * x + b * y + tx
    fun mapY(x: Float, y: Float) = c * x + d * y + ty

    /** The values for [android.graphics.Matrix.setValues]. */
    fun values() = floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)

    /** Where a [width] x [height] picture at (0, 0) ends up. */
    fun mapBox(width: Float, height: Float): Box {
        val xs = floatArrayOf(mapX(0f, 0f), mapX(width, 0f), mapX(0f, height), mapX(width, height))
        val ys = floatArrayOf(mapY(0f, 0f), mapY(width, 0f), mapY(0f, height), mapY(width, height))
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

/** A rectangle in plain numbers. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Turning the camera's photo into the frozen picture: size, rotation and where the screen was. */
object PhotoDecode {
    /**
     * Pixels kept from the photo. Digital zoom crops the sensor and the camera scales the crop up
     * to full size, which adds no detail; so at 3x zoom and more, half the pixels in each
     * direction hold all there is (a quarter of the memory), at 6x a quarter.
     */
    fun sampleSize(cameraZoomRatio: Float): Int {
        var sample = 1
        while (sample * 2 * ZOOM_PER_HALVING <= cameraZoomRatio) sample *= 2
        return sample
    }

    private const val ZOOM_PER_HALVING = 1.5f

    /** Degrees clockwise for an EXIF orientation, or null when it says nothing usable (missing, or a mirror). */
    fun exifDegrees(orientation: Int): Int? = when (orientation) {
        ExifInterface.ORIENTATION_NORMAL -> 0
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> null
    }

    /**
     * How far to turn the decoded photo clockwise to stand it up. The JPEG's own EXIF tag
     * describes its pixels, so it wins; CameraX's rotation (also read from EXIF where the camera
     * wrote it) is the fallback.
     */
    fun uprightRotation(exifDegrees: Int?, cameraXDegrees: Int): Int =
        ((exifDegrees ?: cameraXDegrees) % 360 + 360) % 360

    /** The photo turned [degrees] clockwise (0, 90, 180, 270), as a transform of its pixels. */
    fun rotation(width: Float, height: Float, degrees: Int): Affine = when (degrees) {
        90 -> Affine(0f, -1f, height, 1f, 0f, 0f)
        180 -> Affine(-1f, 0f, width, 0f, -1f, height)
        270 -> Affine(0f, 1f, 0f, -1f, 0f, width)
        else -> Affine(1f, 0f, 0f, 0f, 1f, 0f)
    }

    /** Width and height after turning [degrees]. */
    fun uprightSize(width: Int, height: Int, degrees: Int): Pair<Int, Int> =
        if (degrees == 90 || degrees == 270) height to width else width to height

    /**
     * The transform that draws a photo of [width] x [height] pixels, turned [degrees], so that
     * [crop] (in the photo's pixels: the part the screen showed) fills a view of
     * [viewW] x [viewH], centred, exactly as the live picture did.
     */
    fun baseTransform(width: Float, height: Float, degrees: Int, crop: Box, viewW: Float, viewH: Float): Affine {
        val r = rotation(width, height, degrees)
        // The crop's corners, turned.
        val xs = floatArrayOf(r.mapX(crop.left, crop.top), r.mapX(crop.right, crop.bottom))
        val ys = floatArrayOf(r.mapY(crop.left, crop.top), r.mapY(crop.right, crop.bottom))
        val left = minOf(xs[0], xs[1])
        val top = minOf(ys[0], ys[1])
        val w = max(1f, kotlin.math.abs(xs[1] - xs[0]))
        val h = max(1f, kotlin.math.abs(ys[1] - ys[0]))
        val k = max(viewW / w, viewH / h)
        val ox = -k * left + (viewW - k * w) / 2f
        val oy = -k * top + (viewH - k * h) / 2f
        return Affine(k * r.a, k * r.b, k * r.tx + ox, k * r.c, k * r.d, k * r.ty + oy)
    }
}
