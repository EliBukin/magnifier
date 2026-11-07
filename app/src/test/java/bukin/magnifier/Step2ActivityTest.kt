package bukin.magnifier

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Step 2 in the activity: freeze, colours, brightness, remembered settings, save and share. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Step2ActivityTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var media: PictureStoreTest.FakeMediaStore

    @Before
    fun setUp() {
        MainActivity.cameraStartEnabled = false
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        media = Robolectric.setupContentProvider(PictureStoreTest.FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }

    @After
    fun tearDown() {
        MainActivity.cameraStartEnabled = true
    }

    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()
    private fun MainActivity.view(id: Int): View = findViewById(id)
    private fun MainActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun MainActivity.state(id: Int) = ViewCompat.getStateDescription(view(id))?.toString()

    private fun MainActivity.clickLabel(id: Int): String? {
        val info = AccessibilityNodeInfoCompat.wrap(view(id).createAccessibilityNodeInfo())
        return info.actionList.firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }?.label?.toString()
    }

    /** A paper-grey picture: 200 everywhere. */
    private fun paper(): Bitmap = Bitmap.createBitmap(72, 156, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 200, 200)) }

    /** Runs the main looper until [done], while the activity's worker thread finishes its job. */
    private fun waitFor(done: () -> Boolean) {
        repeat(250) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out")
    }

    @Test
    fun freezingSwapsTheLightAndFocusForSaveAndShare() {
        val activity = launch().get()
        assertTrue(activity.view(R.id.lightButton).isVisible())
        assertFalse(activity.view(R.id.saveButton).isVisible())
        assertEquals("freeze the picture", activity.clickLabel(R.id.freezeButton))
        val row = activity.findViewById<android.view.ViewGroup>(R.id.actionRow)
        fun order() = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isVisible() && it is MaterialButton }.map { it.id }
        assertEquals(listOf(R.id.brightnessButton, R.id.lightButton, R.id.freezeButton, R.id.focusButton, R.id.colourButton), order())

        activity.freezeForTest(paper())
        assertTrue(activity.frozen)
        assertTrue(activity.view(R.id.frozenView).isVisible())
        assertFalse(activity.view(R.id.lightButton).isVisible())
        assertFalse(activity.view(R.id.focusButton).isVisible())
        assertTrue(activity.view(R.id.saveButton).isEnabled)
        assertTrue(activity.view(R.id.shareButton).isEnabled)
        assertEquals("Live", activity.text(R.id.freezeButton))
        assertEquals("Picture frozen", activity.state(R.id.freezeButton))
        assertEquals("go back to the moving picture", activity.clickLabel(R.id.freezeButton))
        // Save and Share in the light's and the focus's places.
        assertEquals(listOf(R.id.brightnessButton, R.id.saveButton, R.id.freezeButton, R.id.shareButton, R.id.colourButton), order())
        // TalkBack skips the live picture hidden underneath.
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, activity.view(R.id.previewView).importantForAccessibility)

        activity.view(R.id.freezeButton).performClick()
        assertFalse(activity.frozen)
        assertFalse(activity.view(R.id.frozenView).isVisible())
        assertTrue(activity.view(R.id.lightButton).isVisible())
        assertFalse(activity.view(R.id.saveButton).isVisible())
        assertEquals("Freeze", activity.text(R.id.freezeButton))
        assertNull(activity.state(R.id.freezeButton))
    }

    @Test
    fun backWhileFrozenGoesBackToLiveAndThenLeaves() {
        val activity = launch().get()
        activity.freezeForTest(paper())
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.frozen)
        assertFalse(activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun theFocusRingGoesWhileFrozenAndZoomIsChosen() {
        val activity = launch().get()
        activity.manualFocus = ManualFocus(10f, calibrated = true)
        activity.onFocusEvent(FocusEvent.CAMERA_RESTARTED)
        activity.setRingMode(RingMode.FOCUS)
        assertEquals(RingMode.FOCUS, activity.ringMode)
        activity.freezeForTest(paper())
        assertEquals(RingMode.ZOOM, activity.ringMode)
        assertEquals("Zoom", activity.text(R.id.ringCaption))
        // Brightness stays on the ring through a freeze, as the picture's brightness.
        activity.setRingMode(RingMode.BRIGHTNESS)
        activity.unfreezeForTest()
        assertEquals(RingMode.BRIGHTNESS, activity.ringMode)
        activity.freezeForTest(paper())
        assertEquals(RingMode.BRIGHTNESS, activity.ringMode)
    }

    @Test
    fun theRingAndVolumeKeysZoomTheFrozenPictureAndEveryStepShows() {
        val activity = launch().get()
        activity.freezeForTest(paper())
        val frozen = activity.findViewById<FrozenView>(R.id.frozenView)
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        // The first frozen view: the ring at its start, like the widest live view, and the
        // value the zoom it was frozen at.
        assertEquals("1.00×", activity.text(R.id.ringValue))
        assertEquals(0f, ring.value, 1e-3f)
        assertTrue(ring.isEnabled)
        // TalkBack's "forward" on the ring is one step, as a tap beside the thumb.
        assertTrue(ring.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(1.110f, frozen.scale, 1e-3f)
        assertEquals("1.11×", activity.text(R.id.ringValue))
        assertEquals("Zoom 1.11 times", activity.view(R.id.ringCentre).contentDescription)
        assertEquals("1.11 times", ViewCompat.getStateDescription(ring))
        assertEquals(5f, ring.value, 1e-2f)
        // The volume keys, taken even at the top; they also bring the ring back to zoom.
        activity.setRingMode(RingMode.BRIGHTNESS)
        val up = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        val labels = mutableListOf("1.11×")
        repeat(20) {
            assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, up))
            labels += activity.text(R.id.ringValue)
        }
        assertEquals(RingMode.ZOOM, activity.ringMode)
        assertEquals(8f, frozen.scale, 1e-3f)
        assertEquals("8.0×", labels.last())
        assertEquals(100f, ring.value, 1e-2f)
        // Every press up to the top changed the number.
        val climb = labels.takeWhile { it != "8.0×" } + "8.0×"
        assertEquals(climb.distinct(), climb)
        // Turning the ring zooms it too, and the value follows: a third of the way is 2x.
        activity.onRingTurned(100f / 3f)
        assertEquals(2f, frozen.scale, 1e-3f)
        assertEquals("2.0×", activity.text(R.id.ringValue))
        activity.onRingStepped(-1)
        assertEquals("1.87×", activity.text(R.id.ringValue))
    }

    @Test
    fun turningTheRingWithAFingerZoomsTheFrozenPicture() {
        val activity = launch().get()
        activity.freezeForTest(paper())
        val root = activity.view(R.id.root)
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        val g = ring.geometry!!
        fun at(angle: Float): Pair<Float, Float> {
            val rad = Math.toRadians(angle.toDouble())
            return (g.cx + g.radius * kotlin.math.sin(rad)).toFloat() to (g.cy - g.radius * kotlin.math.cos(rad)).toFloat()
        }
        fun send(action: Int, p: Pair<Float, Float>) {
            val now = android.os.SystemClock.uptimeMillis()
            val e = android.view.MotionEvent.obtain(now, now, action, p.first, p.second, 0)
            ring.dispatchTouchEvent(e)
            e.recycle()
        }
        // From the thumb at the start, a third of the arc along: 2x.
        send(android.view.MotionEvent.ACTION_DOWN, at(-75f))
        for (i in 1..25) send(android.view.MotionEvent.ACTION_MOVE, at(-75f + 50f * i / 25))
        send(android.view.MotionEvent.ACTION_UP, at(-25f))
        assertEquals(2f, activity.findViewById<FrozenView>(R.id.frozenView).scale, 0.02f)
        assertEquals("2.0×", activity.text(R.id.ringValue))
        // A tap on the ring's right part: one step in, to the next twentieth (0.35 of the way).
        send(android.view.MotionEvent.ACTION_DOWN, at(50f))
        send(android.view.MotionEvent.ACTION_UP, at(50f))
        assertEquals(2.07f, activity.findViewById<FrozenView>(R.id.frozenView).scale, 0.01f)
    }

    @Test
    fun theColourButtonCyclesFiltersOnlyThePictureAndIsRemembered() {
        val controller = launch()
        val activity = controller.get()
        val button = R.id.colourButton
        assertEquals("Normal", activity.text(button))
        assertEquals("Normal", activity.state(button))
        assertEquals("change the colours", activity.clickLabel(button))
        assertEquals(View.LAYER_TYPE_NONE, activity.view(R.id.previewView).layerType)

        activity.view(button).performClick()
        assertEquals(ColorMode.HIGH_CONTRAST, activity.colourMode)
        assertEquals("Contrast", activity.text(button))
        assertEquals("High contrast", activity.state(button))
        assertEquals("High contrast", activity.messageShown())
        // Not read out twice: TalkBack hears the button's new state.
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, activity.view(R.id.messageView).accessibilityLiveRegion)
        // The live picture is filtered through its own layer; the controls never are.
        assertEquals(View.LAYER_TYPE_HARDWARE, activity.view(R.id.previewView).layerType)
        for (id in listOf(R.id.controls, R.id.actionRow, R.id.root)) assertEquals(View.LAYER_TYPE_NONE, activity.view(id).layerType)

        activity.view(button).performClick()
        activity.view(button).performClick()
        assertEquals(ColorMode.YELLOW_ON_BLACK, activity.colourMode)
        assertEquals("Yellow", activity.text(button))
        assertEquals("Yellow on black", activity.state(button))
        // Remembered for the next start.
        controller.pause().stop().destroy()
        val again = launch().get()
        assertEquals(ColorMode.YELLOW_ON_BLACK, again.colourMode)
        assertEquals(View.LAYER_TYPE_HARDWARE, again.view(R.id.previewView).layerType)
        again.view(button).performClick()
        assertEquals(ColorMode.NORMAL, again.colourMode)
        assertEquals(View.LAYER_TYPE_NONE, again.view(R.id.previewView).layerType)
    }

    @Test
    fun frozenBrightnessIsTheSameRingThroughTheColourMatrix() {
        val activity = launch().get()
        activity.setColourMode(ColorMode.INVERTED, announce = false)
        activity.freezeForTest(paper())
        activity.view(R.id.brightnessButton).performClick()
        assertEquals(RingMode.BRIGHTNESS, activity.ringMode)
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        assertEquals(-10f, ring.valueFrom, 0f)
        assertEquals(10f, ring.valueTo, 0f)
        assertEquals(1f, ring.stepSize, 0f)
        assertEquals(0f, ring.value, 0f)
        assertEquals(0f, ring.fillFrom, 0f)
        activity.onRingStepped(1)
        activity.onRingStepped(1)
        assertEquals(2, activity.frozenLevel)
        assertEquals("+2", activity.text(R.id.ringValue))
        assertEquals("+2", activity.text(R.id.brightnessButton))
        assertEquals("2 steps lighter", ViewCompat.getStateDescription(ring))
        // Inverted: "lighter" is less light on the page, so the inverted screen gets lighter.
        assertArrayEquals(ColorMode.INVERTED.matrix(Brightness.gain(-2)), activity.pictureMatrix(), 1e-6f)
        val paperOnScreen = Color.red(ColorMode.apply(activity.pictureMatrix(), Color.rgb(200, 200, 200)))
        assertTrue("inverted paper at +2: $paperOnScreen", paperOnScreen > 55)
        // In Normal the same position is more light.
        activity.setColourMode(ColorMode.NORMAL, announce = false)
        assertArrayEquals(ColorMode.NORMAL.matrix(Brightness.gain(2)), activity.pictureMatrix(), 1e-6f)
        activity.setColourMode(ColorMode.INVERTED, announce = false)
        assertNotNull(activity.findViewById<FrozenView>(R.id.frozenView).pictureFilter)
        // Back to normal colours and level 0: no filter at all.
        activity.setColourMode(ColorMode.NORMAL, announce = false)
        activity.onRingTurned(0f)
        assertNull(activity.findViewById<FrozenView>(R.id.frozenView).pictureFilter)
        // Turned darker.
        activity.onRingTurned(-3f)
        assertEquals("\u22123", activity.text(R.id.ringValue))
        // Each freeze starts at "as taken".
        activity.unfreezeForTest()
        activity.freezeForTest(paper())
        assertEquals(0, activity.frozenLevel)
        assertEquals("0", activity.text(R.id.brightnessButton))
    }

    @Test
    fun theZoomIsRememberedAndBrightnessAndFocusAreNot() {
        MagnifierSettings(app).apply {
            zoomLinear = 0.4f
            colourMode = ColorMode.INVERTED
        }
        val controller = launch()
        val activity = controller.get()
        assertEquals(0.4f, activity.wantedLinearZoom, 0f)
        assertEquals(ColorMode.INVERTED, activity.colourMode)
        assertEquals(FocusMode.AUTO, activity.focusMode)
        controller.pause().stop()
        assertEquals(0.4f, MagnifierSettings(app).zoomLinear, 0f)
    }

    @Test
    fun aZoomChangeIsSavedSoonEvenIfTheAppIsKilledBeforeStopping() {
        val activity = launch().get()
        // No camera in Robolectric, so the volume keys aren't taken; the ring is the user's.
        activity.onRingTurned(35f)
        assertEquals(0.35f, activity.wantedLinearZoom, 1e-4f)
        assertEquals(0f, MagnifierSettings(app).zoomLinear, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(0.35f, MagnifierSettings(app).zoomLinear, 1e-4f)
    }

    @Test
    @Config(sdk = [30])
    fun savingOnAndroidElevenAsksForNothingAndSavesTheFilteredPicture() {
        val activity = launch().get()
        activity.setColourMode(ColorMode.INVERTED, announce = false)
        activity.freezeForTest(paper())
        activity.onSaveButton()
        assertNull(shadowOf(activity).lastRequestedPermission)
        waitFor { media.inserted.isNotEmpty() && activity.messageShown() == "The whole picture is saved in Pictures, Bukin Magnifier." }
        val (row, values) = media.inserted.single()
        assertEquals("Pictures/Bukin Magnifier", values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertTrue(values.getAsString(MediaStore.Images.Media.DISPLAY_NAME).matches(Regex("BukinMagnifier_\\d{8}_\\d{6}\\.jpg")))
        // The whole picture (72 x 156), inverted: paper 200 -> 55.
        val saved = BitmapFactory.decodeFile(media.files.getValue(row).path)
        assertEquals(72, saved.width)
        assertEquals(156, saved.height)
        val pixel = saved.getPixel(36, 78)
        assertEquals(55f, Color.red(pixel).toFloat(), 3f)
        assertEquals(55f, Color.blue(pixel).toFloat(), 3f)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, activity.view(R.id.messageView).accessibilityLiveRegion)
    }

    @Test
    @Config(sdk = [28])
    fun savingOnAndroidNineAsksForStorageFirstAndOnlyThen() {
        val activity = launch().get()
        // Not at start.
        assertNull(shadowOf(activity).lastRequestedPermission)
        activity.setColourMode(ColorMode.YELLOW_ON_BLACK, announce = false)
        activity.freezeForTest(paper())
        activity.view(R.id.saveButton).performClick()
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions)
        assertTrue(media.inserted.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        waitFor { media.inserted.isNotEmpty() && activity.messageShown()?.startsWith("The whole picture") == true }
        @Suppress("DEPRECATION")
        val path = media.inserted.single().second.getAsString(MediaStore.Images.Media.DATA)
        assertTrue(path, path.contains("/Pictures/Bukin Magnifier/BukinMagnifier_"))
        // Yellow on black: the paper is black, never any blue.
        val saved = BitmapFactory.decodeFile(path)
        val pixel = saved.getPixel(10, 10)
        assertTrue(Color.red(pixel) < 6 && Color.blue(pixel) < 6)
    }

    @Test
    @Config(sdk = [28])
    fun aRefusedStoragePermissionSaysWhatToDo() {
        // Refused, but Android will ask again.
        val first = launch().get()
        first.freezeForTest(paper())
        first.onSaveButton()
        first.answerStorage(granted = false, canAskAgain = true)
        assertEquals("To save pictures, allow the app to store them.", first.messageShown())
        // Refused for good.
        val second = launch().get()
        second.freezeForTest(paper())
        second.onSaveButton()
        second.answerStorage(granted = false, canAskAgain = false)
        assertEquals("Storage is turned off for this app. To save pictures, allow it in the app's settings.", second.messageShown())
        assertTrue(media.inserted.isEmpty())
    }

    private fun MainActivity.answerStorage(granted: Boolean, canAskAgain: Boolean) {
        val request = shadowOf(this).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE, canAskAgain)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        @Suppress("DEPRECATION")
        onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
    }

    @Test
    fun shareOpensTheShareSheetWithAReadGrantFromTheCache() {
        val activity = launch().get()
        activity.freezeForTest(paper())
        activity.view(R.id.shareButton).performClick()
        var started: Intent? = null
        waitFor {
            started = shadowOf(activity).nextStartedActivity
            started != null
        }
        val chooser = started!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share the picture", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${activity.packageName}.fileprovider", uri.authority)
        assertTrue(uri.path!!.startsWith("/shared/BukinMagnifier_"))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        // The picture is in cache/shared, and nothing was saved to Pictures.
        assertTrue(File(PictureStore.shareDir(activity), uri.lastPathSegment!!).isFile)
        assertTrue(media.inserted.isEmpty())
    }

    @Test
    fun theFrozenButtonsAreBig() {
        val activity = launch().get()
        activity.freezeForTest(paper())
        val root = activity.view(R.id.root)
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
        val density = activity.resources.displayMetrics.density
        for (id in listOf(R.id.brightnessButton, R.id.saveButton, R.id.shareButton, R.id.freezeButton, R.id.colourButton)) {
            val button = activity.findViewById<MaterialButton>(id)
            assertTrue(button.height / density >= 56f && button.width / density >= 56f)
        }
    }

    private fun View.isVisible() = visibility == View.VISIBLE
}
