package bukin.magnifier

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
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
            R.id.previewView to "Camera picture. Tap to focus.",
            R.id.lightButton to "Light",
            R.id.zoomSlider to "Zoom",
            R.id.zoomIn to "Zoom in",
            R.id.zoomOut to "Zoom out",
        )
        for ((id, label) in labels) {
            assertEquals(label, activity.findViewById<View>(id).contentDescription?.toString())
        }
        val retry = activity.findViewById<TextView>(R.id.errorRetry)
        assertEquals("Try again", retry.text.toString())
    }

    @Test
    fun touchTargetsAreAtLeast56dp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = launch().get()
        val density = activity.resources.displayMetrics.density
        for (id in listOf(R.id.lightButton, R.id.zoomIn, R.id.zoomOut, R.id.zoomSlider)) {
            val view = activity.findViewById<View>(id)
            assertTrue("height of ${activity.resources.getResourceEntryName(id)}", view.height / density >= 56f)
        }
        for (id in listOf(R.id.lightButton, R.id.zoomIn, R.id.zoomOut)) {
            val view = activity.findViewById<MaterialButton>(id)
            assertTrue("width of ${activity.resources.getResourceEntryName(id)}", view.width / density >= 56f)
        }
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
