package bukin.magnifier

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-port-xhdpi")
class MainActivityTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MainActivity.cameraStartEnabled = false
    }

    @After
    fun tearDown() {
        MainActivity.cameraStartEnabled = true
    }

    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun MainActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private object ContextCompatColor {
        fun accent(activity: MainActivity) = androidx.core.content.ContextCompat.getColor(activity, R.color.accent)
    }

    private fun MainActivity.keepsScreenOn() =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun MainActivity.volumeKeyTaken(code: Int) = onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))

    private fun rationale(show: Boolean) =
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, show)

    /** Taps the permission button, checks the camera was asked for, and answers with [granted]. */
    private fun MainActivity.askAndAnswer(granted: Boolean) {
        findViewById<View>(R.id.permissionButton).performClick()
        val request = shadowOf(this).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), request.requestedPermissions)
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        @Suppress("DEPRECATION") // What Android calls; it hands the result to the ActivityResult API.
        onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
    }

    @Test
    fun firstStartExplainsBeforeAsking() {
        val activity = launch().get()
        assertEquals(PermissionScreen.EXPLAIN, activity.permissionScreen)
        assertTrue(activity.findViewById<View>(R.id.permissionPanel).isVisible)
        assertFalse(activity.findViewById<View>(R.id.controls).isVisible)
        assertEquals("Camera access", activity.text(R.id.permissionTitle))
        assertEquals("Continue", activity.text(R.id.permissionButton))
        // Nothing was asked yet: the explanation comes first.
        assertEquals(null, shadowOf(activity).lastRequestedPermission)
        assertFalse(activity.keepsScreenOn())
    }

    @Test
    fun volumeKeysChangeTheVolumeOnThePermissionScreen() {
        val activity = launch().get()
        assertFalse(activity.cameraInFront())
        assertFalse(activity.volumeKeyTaken(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(activity.volumeKeyTaken(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun aDenialThatCanBeAskedAgainOffersGrant() {
        val activity = launch().get()
        rationale(true)
        activity.askAndAnswer(granted = false)
        assertEquals(PermissionScreen.DENIED, activity.permissionScreen)
        assertEquals("The magnifier needs the camera", activity.text(R.id.permissionTitle))
        assertEquals("Grant", activity.text(R.id.permissionButton))
        // Grant asks again.
        activity.askAndAnswer(granted = false)
    }

    @Test
    fun aFinalDenialOpensTheAppsSettings() {
        val activity = launch().get()
        rationale(false)
        activity.askAndAnswer(granted = false)
        assertEquals(PermissionScreen.BLOCKED, activity.permissionScreen)
        assertEquals("Open settings", activity.text(R.id.permissionButton))

        activity.findViewById<View>(R.id.permissionButton).performClick()
        val started: Intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${activity.packageName}", started.dataString)
    }

    @Test
    fun theNextStartRemembersThatTheAppAlreadyAsked() {
        launch().get().also { rationale(false) }.askAndAnswer(granted = false)
        val again = launch().get()
        assertEquals(PermissionScreen.BLOCKED, again.permissionScreen)
        // The flag lives in the device-only state file, never in the backed-up settings.
        val state = app.getSharedPreferences(MainActivity.STATE_PREFS, Context.MODE_PRIVATE)
        assertTrue(state.getBoolean(MainActivity.KEY_ASKED_CAMERA, false))
    }

    @Test
    fun grantingShowsTheCameraControlsAndKeepsTheScreenOn() {
        val activity = launch().get()
        activity.askAndAnswer(granted = true)
        assertEquals(PermissionScreen.CAMERA, activity.permissionScreen)
        assertFalse(activity.findViewById<View>(R.id.permissionPanel).isVisible)
        assertTrue(activity.findViewById<View>(R.id.controls).isVisible)
        assertFalse(activity.findViewById<View>(R.id.errorPanel).isVisible)
        assertTrue(activity.keepsScreenOn())
    }

    @Test
    fun comingBackFromSettingsWithThePermissionShowsTheCamera() {
        val controller = launch()
        rationale(false)
        controller.get().askAndAnswer(granted = false)
        assertEquals(PermissionScreen.BLOCKED, controller.get().permissionScreen)
        controller.pause().stop()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        controller.start().resume()
        assertEquals(PermissionScreen.CAMERA, controller.get().permissionScreen)
        assertTrue(controller.get().findViewById<View>(R.id.controls).isVisible)
    }

    @Test
    fun aPermissionAlreadyGrantedGoesStraightToTheCamera() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        assertEquals(PermissionScreen.CAMERA, activity.permissionScreen)
        assertEquals(null, shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun everyControlHasALabelForTalkBack() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        val labels = mapOf(
            R.id.previewView to "Camera picture. Tap to focus there and lock it.",
            R.id.frozenView to "Frozen picture. Pinch or double tap to zoom, drag to move around.",
            R.id.lensRing to "Zoom",
            R.id.brightnessButton to "Brightness",
            R.id.lightButton to "Light",
            R.id.focusButton to "Focus",
            R.id.colourButton to "Colours",
            R.id.saveButton to "Save the whole picture",
            R.id.shareButton to "Share the whole picture",
        )
        for ((id, label) in labels) {
            assertEquals(label, activity.findViewById<View>(id).contentDescription?.toString())
        }
        // Buttons whose text is their label.
        val texts = mapOf(R.id.freezeButton to "Freeze", R.id.errorRetry to "Try again")
        for ((id, text) in texts) assertEquals(text, activity.text(id))
        // No camera yet: the ring's centre says what it adjusts, with no value.
        assertEquals("Zoom", activity.text(R.id.ringCaption))
        assertEquals("", activity.text(R.id.ringValue))
        assertEquals("Zoom", activity.findViewById<View>(R.id.ringCentre).contentDescription)
        // The brightness button shows the level, and says it.
        assertEquals("0", activity.text(R.id.brightnessButton))
        assertEquals("Normal", ViewCompat.getStateDescription(activity.findViewById(R.id.brightnessButton)))
        assertEquals("adjust the brightness on the ring", activity.clickLabel(R.id.brightnessButton))
    }

    @Test
    fun touchTargetsAreAtLeast56dpLiveAndFrozen() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        activity.showFocusRing()
        val live = listOf(R.id.brightnessButton, R.id.lightButton, R.id.freezeButton, R.id.focusButton, R.id.colourButton)
        activity.assertTargets(live)
        // The ring's centre, a button back to zoom while the ring adjusts something else.
        activity.findViewById<View>(R.id.brightnessButton).performClick()
        activity.relayout()
        activity.assertTargets(live + R.id.ringCentre)
        activity.freezeForTest(Bitmap.createBitmap(72, 156, Bitmap.Config.ARGB_8888))
        activity.relayout()
        activity.assertTargets(listOf(R.id.brightnessButton, R.id.saveButton, R.id.freezeButton, R.id.shareButton, R.id.colourButton))
        // The ring: its band is 56dp thick, and as wide as the panel.
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        val density = activity.resources.displayMetrics.density
        assertTrue(2 * ring.geometry!!.bandHalf / density >= 48f)
        assertEquals(348f, ring.width / density, 0.5f)
    }

    private fun MainActivity.assertTargets(ids: List<Int>) {
        val density = resources.displayMetrics.density
        for (id in ids) {
            val view = findViewById<View>(id)
            val name = resources.getResourceEntryName(id)
            assertTrue("$name is showing", view.isShown)
            assertTrue("height of $name: ${view.height / density}", view.height / density >= 56f)
            assertTrue("width of $name: ${view.width / density}", view.width / density >= 56f)
        }
    }

    private fun MainActivity.relayout() {
        findViewById<View>(R.id.root).run {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(left, top, right, bottom)
        }
    }

    @Test
    fun thePanelCoversAtMostFortyPercentAndFreezeIsTheBiggestAction() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        activity.showFocusRing()
        activity.relayout()
        val density = activity.resources.displayMetrics.density
        // 360 x 780dp: a 720 x 1560 screen at xhdpi.
        val root = activity.findViewById<View>(R.id.root).height
        assertEquals(780f, root / density, 1f)
        val panel = activity.findViewById<View>(R.id.controls).height
        // With a 48dp navigation bar below it, as on the A20.
        val share = (panel + 48 * density) / root
        assertTrue("the panel covers ${share * 100}%", share <= 0.40f)
        println("360 x 780dp: the panel covers ${share * 100}% with a 48dp navigation bar")
        val freeze = activity.findViewById<View>(R.id.freezeButton)
        for (id in listOf(R.id.brightnessButton, R.id.lightButton, R.id.focusButton, R.id.colourButton)) {
            val side = activity.findViewById<View>(id)
            assertTrue(freeze.width > side.width * 1.4f)
            // Freeze rises into the ring; all bottoms line up.
            assertEquals(freeze.bottom, side.bottom)
            assertTrue(freeze.top < side.top)
        }
        // The side buttons sit below the ring's ends and their thumb.
        val ring = activity.findViewById<LensRingView>(R.id.lensRing)
        val g = ring.geometry!!
        val sideTop = activity.findViewById<View>(R.id.brightnessButton).top + activity.findViewById<View>(R.id.actionRow).top
        assertTrue(g.yFor(0f) + LensRingView.THUMB_RADIUS_DP * density <= sideTop)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-hdpi")
    fun onASmall320dpPhoneEveryButtonStillFitsAndIsBigEnough() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        activity.relayout()
        val density = activity.resources.displayMetrics.density
        val width = activity.findViewById<View>(R.id.root).width
        val row = listOf(R.id.brightnessButton, R.id.lightButton, R.id.freezeButton, R.id.focusButton, R.id.colourButton)
        activity.assertTargets(row)
        val rowView = activity.findViewById<View>(R.id.actionRow)
        val panel = activity.findViewById<View>(R.id.panel)
        for (id in row) {
            val v = activity.findViewById<View>(id)
            val left = panel.left + rowView.left + v.left
            assertTrue("${activity.resources.getResourceEntryName(id)} inside the screen", left >= 0 && left + v.width <= width)
        }
        // Buttons don't overlap.
        val lefts = row.map { activity.findViewById<View>(it) }
        for (i in 0 until lefts.size - 1) assertTrue(lefts[i].right <= lefts[i + 1].left)
        val share = (activity.findViewById<View>(R.id.controls).height + 48 * density) / activity.findViewById<View>(R.id.root).height
        // Not the 40% target (that is for 720 x 1560), but well under half of a short 5:3 screen.
        assertTrue("the panel covers ${share * 100}%", share <= 0.45f)
        println("320 x 640dp: the panel covers ${share * 100}% with a 48dp navigation bar")
    }

    /** Pretends the camera has manual focus, so the focus ring is possible (Robolectric has no camera). */
    private fun MainActivity.showFocusRing() {
        manualFocus = ManualFocus(minimumFocusDiopters = 10f, calibrated = true)
        onFocusEvent(FocusEvent.CAMERA_RESTARTED) // re-renders, staying in Auto
        relayout()
    }

    private fun MainActivity.clickLabel(id: Int): String? {
        val info = AccessibilityNodeInfoCompat.wrap(findViewById<View>(id).createAccessibilityNodeInfo())
        return info.actionList.firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }?.label?.toString()
    }

    private fun MainActivity.focusButtonSays(): Triple<String, String?, String?> {
        val button = findViewById<MaterialButton>(R.id.focusButton)
        return Triple(button.text.toString(), ViewCompat.getStateDescription(button)?.toString(), clickLabel(R.id.focusButton))
    }

    @Test
    fun withoutManualFocusTheFocusButtonLocksAndUnlocksAndTheRingKeepsZoom() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        assertEquals(Triple("Auto", "Auto", "lock the focus on the middle of the picture"), activity.focusButtonSays())
        // The old toggle: Auto -> Locked on the middle -> Auto. The ring stays on zoom.
        activity.findViewById<View>(R.id.focusButton).performClick()
        assertEquals(FocusMode.LOCKED, activity.focusMode)
        assertEquals(RingMode.ZOOM, activity.ringMode)
        assertEquals(Triple("Locked", "Locked", "go back to auto focus"), activity.focusButtonSays())
        activity.findViewById<View>(R.id.focusButton).performClick()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        // Without manual focus, nothing can take the focus to Manual or put it on the ring.
        activity.onFocusEvent(FocusEvent.FOCUS_SLIDER)
        assertEquals(FocusMode.AUTO, activity.focusMode)
        activity.setRingMode(RingMode.FOCUS)
        assertEquals(RingMode.ZOOM, activity.ringMode)
    }

    @Test
    fun withManualFocusTheFocusButtonPutsTheFocusOnTheRing() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller = launch()
        val activity = controller.get()
        activity.showFocusRing()
        val button = activity.findViewById<MaterialButton>(R.id.focusButton)
        assertEquals(Triple("Auto", "Auto", "set the focus distance on the ring"), activity.focusButtonSays())

        // Auto: the ring goes to the focus distance; the centre says "Focus, Auto".
        button.performClick()
        assertEquals(RingMode.FOCUS, activity.ringMode)
        assertEquals(FocusMode.AUTO, activity.focusMode)
        assertEquals("Focus", activity.text(R.id.ringCaption))
        assertEquals("Auto", activity.text(R.id.ringValue))
        assertEquals("Focus distance", activity.findViewById<View>(R.id.lensRing).contentDescription)
        assertEquals(Triple("Auto", "Auto", "show the zoom on the ring"), activity.focusButtonSays())

        // Turning the ring is Manual, with the distance in the centre.
        activity.onRingTurned(50f)
        assertEquals(FocusMode.MANUAL, activity.focusMode)
        assertEquals("20 cm", activity.text(R.id.ringValue))
        assertEquals("20 centimetres", ViewCompat.getStateDescription(activity.findViewById(R.id.lensRing)))
        assertEquals(Triple("Manual", "Manual", "go back to auto focus"), activity.focusButtonSays())

        // The button goes back to Auto in one tap; the ring stays on the focus, showing "Auto".
        button.performClick()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        assertEquals(RingMode.FOCUS, activity.ringMode)
        assertEquals("Auto", activity.text(R.id.ringValue))
        // Again: the ring back to zoom.
        button.performClick()
        assertEquals(RingMode.ZOOM, activity.ringMode)

        // A tap on the picture locks; from Locked the button is "back to Auto", wherever the ring is.
        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        assertEquals(Triple("Locked", "Locked", "go back to auto focus"), activity.focusButtonSays())
        button.performClick()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        assertEquals(RingMode.ZOOM, activity.ringMode)

        // Leaving the app ends Manual.
        button.performClick()
        activity.onRingTurned(30f)
        controller.pause().stop()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        controller.start().resume()
        assertEquals(Triple("Auto", "Auto", "show the zoom on the ring"), activity.focusButtonSays())
    }

    @Test
    fun theFocusRingStepsAndTakesOverEvenAtItsEnds() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        activity.showFocusRing()
        activity.setRingMode(RingMode.FOCUS)
        activity.onRingTurned(40f)
        assertEquals(FocusMode.MANUAL, activity.focusMode)
        // A step is a twentieth, towards far (+) or near (−).
        activity.onRingStepped(1)
        assertEquals(45f, activity.focusValue, 1e-4f)
        activity.onRingStepped(-1)
        activity.onRingStepped(-1)
        assertEquals(35f, activity.focusValue, 1e-4f)
        // A tap on the picture locks the focus again.
        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        assertEquals(FocusMode.LOCKED, activity.focusMode)
        // At the near end, a step still takes over.
        activity.onRingTurned(0f)
        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        activity.onRingStepped(-1)
        assertEquals(0f, activity.focusValue, 1e-4f)
        assertEquals(FocusMode.MANUAL, activity.focusMode)
        assertEquals("10 cm", activity.text(R.id.ringValue))
        // When the camera turns out to ignore manual focus, the ring goes back to zoom.
        activity.manualFocus = null
        activity.onFocusEvent(FocusEvent.MANUAL_IGNORED)
        assertEquals(RingMode.ZOOM, activity.ringMode)
        assertEquals(FocusMode.AUTO, activity.focusMode)
    }

    @Test
    fun theBrightnessButtonAndTheCentreTakeTheRingThereAndBack() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        val brightness = activity.findViewById<MaterialButton>(R.id.brightnessButton)
        val centre = activity.findViewById<View>(R.id.ringCentre)
        assertEquals(RingMode.ZOOM, activity.ringMode)
        assertFalse(centre.isClickable)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, centre.accessibilityLiveRegion)

        brightness.performClick()
        assertEquals(RingMode.BRIGHTNESS, activity.ringMode)
        assertEquals("Brightness", activity.text(R.id.ringCaption))
        assertEquals("0", activity.text(R.id.ringValue))
        assertEquals("Brightness", activity.findViewById<View>(R.id.lensRing).contentDescription)
        assertEquals("Normal", ViewCompat.getStateDescription(activity.findViewById(R.id.lensRing)))
        assertEquals("show the zoom on the ring", activity.clickLabel(R.id.brightnessButton))
        // The centre is now a button back to zoom, and says what it shows.
        assertTrue(centre.isClickable)
        assertEquals("Brightness, Normal", centre.contentDescription)
        assertEquals("show the zoom on the ring", activity.clickLabel(R.id.ringCentre))
        // Highlighted while on the ring.
        assertEquals(ContextCompatColor.accent(activity), brightness.backgroundTintList!!.defaultColor)

        centre.performClick()
        assertEquals(RingMode.ZOOM, activity.ringMode)
        assertEquals("Zoom", activity.text(R.id.ringCaption))
        assertFalse(centre.isClickable)
        // The active button again goes back too.
        brightness.performClick()
        brightness.performClick()
        assertEquals(RingMode.ZOOM, activity.ringMode)
    }

    @Test
    fun theLightWaitsForTheCamera() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        val light = activity.findViewById<MaterialButton>(R.id.lightButton)
        assertFalse(light.isEnabled)
        assertFalse(activity.findViewById<View>(R.id.lensRing).isEnabled)
        // No camera bound: the keys still change the volume.
        assertFalse(activity.volumeKeyTaken(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
