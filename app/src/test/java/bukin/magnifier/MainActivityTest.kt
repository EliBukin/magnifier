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
            R.id.lightButton to "Light",
            R.id.zoomSlider to "Zoom",
            R.id.zoomIn to "Zoom in",
            R.id.zoomOut to "Zoom out",
            R.id.focusButton to "Focus",
            R.id.focusSlider to "Focus distance",
            R.id.focusNear to "Focus nearer",
            R.id.focusFar to "Focus farther",
            R.id.colourButton to "Colours",
            R.id.brightnessSlider to "Brightness",
            R.id.brightnessDown to "Darker",
            R.id.brightnessUp to "Lighter",
            R.id.saveButton to "Save the whole picture",
            R.id.shareButton to "Share the whole picture",
        )
        for ((id, label) in labels) {
            assertEquals(label, activity.findViewById<View>(id).contentDescription?.toString())
        }
        // Buttons whose text is their label.
        val texts = mapOf(R.id.freezeButton to "Freeze", R.id.tabBrightness to "Brightness", R.id.tabFocus to "Focus", R.id.errorRetry to "Try again")
        for ((id, text) in texts) assertEquals(text, activity.text(id))
        // No camera yet: the zoom tab says what it is.
        assertEquals("Zoom", activity.text(R.id.tabZoom))
    }

    @Test
    fun touchTargetsAreAtLeast56dpInEveryRowAndMode() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        activity.showFocusSlider()
        val tabs = listOf(R.id.tabZoom, R.id.tabBrightness, R.id.tabFocus)
        val live = listOf(R.id.colourButton, R.id.lightButton, R.id.freezeButton, R.id.focusButton)
        val rows = mapOf(
            R.id.tabZoom to listOf(R.id.zoomOut, R.id.zoomSlider, R.id.zoomIn),
            R.id.tabBrightness to listOf(R.id.brightnessDown, R.id.brightnessSlider, R.id.brightnessUp),
            R.id.tabFocus to listOf(R.id.focusNear, R.id.focusSlider, R.id.focusFar),
        )
        for ((tab, row) in rows) {
            activity.findViewById<View>(tab).performClick()
            activity.relayout()
            activity.assertTargets(tabs + live + row)
        }
        activity.freezeForTest(Bitmap.createBitmap(72, 156, Bitmap.Config.ARGB_8888))
        activity.relayout()
        activity.assertTargets(listOf(R.id.colourButton, R.id.saveButton, R.id.freezeButton, R.id.shareButton, R.id.zoomOut, R.id.zoomSlider, R.id.zoomIn))
    }

    private fun MainActivity.assertTargets(ids: List<Int>) {
        val density = resources.displayMetrics.density
        for (id in ids) {
            val view = findViewById<View>(id)
            val name = resources.getResourceEntryName(id)
            assertTrue("$name is showing", view.isShown)
            assertTrue("height of $name: ${view.height / density}", view.height / density >= 56f)
            if (view is MaterialButton) assertTrue("width of $name: ${view.width / density}", view.width / density >= 56f)
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
        activity.showFocusSlider()
        activity.relayout()
        val density = activity.resources.displayMetrics.density
        val root = activity.findViewById<View>(R.id.root).height
        val panel = activity.findViewById<View>(R.id.controls).height
        // With a 48dp navigation bar below it, as on the A20.
        val share = (panel + 48 * density) / root
        assertTrue("the panel covers ${share * 100}%", share <= 0.40f)
        val freeze = activity.findViewById<View>(R.id.freezeButton)
        for (id in listOf(R.id.colourButton, R.id.lightButton, R.id.focusButton)) {
            assertTrue(freeze.width > activity.findViewById<View>(id).width * 1.4f)
        }
    }

    /** Pretends the camera has manual focus, so the focus row shows (Robolectric has no camera). */
    private fun MainActivity.showFocusSlider() {
        manualFocus = ManualFocus(minimumFocusDiopters = 10f, calibrated = true)
        onFocusEvent(FocusEvent.CAMERA_RESTARTED) // re-renders, staying in Auto
        relayout()
    }

    private fun MainActivity.focusButtonSays(): Triple<String, String?, String?> {
        val button = findViewById<MaterialButton>(R.id.focusButton)
        val info = AccessibilityNodeInfoCompat.wrap(button.createAccessibilityNodeInfo())
        val click = info.actionList.firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }
        return Triple(button.text.toString(), ViewCompat.getStateDescription(button)?.toString(), click?.label?.toString())
    }

    @Test
    fun theFocusStartsInAutoWithNoSliderWhereThereIsNoManualFocus() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        assertEquals(Triple("Auto", "Auto", "lock the focus on the middle of the picture"), activity.focusButtonSays())
        assertFalse(activity.findViewById<View>(R.id.focusRow).isVisible)
        assertFalse(activity.findViewById<View>(R.id.tabFocus).isVisible)
        // Without manual focus, the slider can't take over.
        activity.onFocusEvent(FocusEvent.FOCUS_SLIDER)
        assertEquals(FocusMode.AUTO, activity.focusMode)
    }

    @Test
    fun theFocusButtonShowsTheModeAndLeavingTheAppReturnsToAuto() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val controller = launch()
        val activity = controller.get()
        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        assertEquals(FocusMode.LOCKED, activity.focusMode)
        assertEquals(Triple("Locked", "Locked", "go back to auto focus"), activity.focusButtonSays())

        activity.showFocusSlider()
        // The focus tab shows; its row only once it is chosen.
        assertTrue(activity.findViewById<View>(R.id.tabFocus).isVisible)
        assertFalse(activity.findViewById<View>(R.id.focusRow).isVisible)
        activity.findViewById<View>(R.id.tabFocus).performClick()
        assertTrue(activity.findViewById<View>(R.id.focusRow).isVisible)
        assertFalse(activity.findViewById<View>(R.id.zoomRow).isVisible)
        activity.onFocusEvent(FocusEvent.FOCUS_SLIDER)
        assertEquals(Triple("Manual", "Manual", "go back to auto focus"), activity.focusButtonSays())

        // The button goes back to Auto.
        activity.findViewById<View>(R.id.focusButton).performClick()
        assertEquals(FocusMode.AUTO, activity.focusMode)

        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        controller.pause().stop()
        assertEquals(FocusMode.AUTO, activity.focusMode)
        controller.start().resume()
        assertEquals(Triple("Auto", "Auto", "lock the focus on the middle of the picture"), activity.focusButtonSays())
    }

    @Test
    fun movingTheFocusSliderSwitchesToManual() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        activity.showFocusSlider()
        val slider = activity.findViewById<com.google.android.material.slider.Slider>(R.id.focusSlider)
        slider.value = 40f
        assertEquals(FocusMode.MANUAL, activity.focusMode)
        // The far button steps a twentieth towards far.
        activity.findViewById<View>(R.id.focusFar).performClick()
        assertEquals(45f, slider.value, 1e-4f)
        activity.findViewById<View>(R.id.focusNear).performClick()
        activity.findViewById<View>(R.id.focusNear).performClick()
        assertEquals(35f, slider.value, 1e-4f)
        // A tap on the picture locks the focus again.
        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        assertEquals(FocusMode.LOCKED, activity.focusMode)
        // At an end of the slider, its button still takes over.
        slider.value = 0f
        activity.onFocusEvent(FocusEvent.TAP_PICTURE)
        activity.findViewById<View>(R.id.focusNear).performClick()
        assertEquals(0f, slider.value, 1e-4f)
        assertEquals(FocusMode.MANUAL, activity.focusMode)
    }

    @Test
    fun theLightWaitsForTheCamera() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        val light = activity.findViewById<MaterialButton>(R.id.lightButton)
        assertFalse(light.isEnabled)
        assertFalse(activity.findViewById<View>(R.id.zoomSlider).isEnabled)
        // No camera bound: the keys still change the volume.
        assertFalse(activity.volumeKeyTaken(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
