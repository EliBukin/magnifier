package bukin.magnifier

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import bukin.magnifier.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import java.util.concurrent.TimeUnit

/**
 * The magnifier: the rear camera full screen, with zoom (slider, pinch, volume keys, buttons),
 * tap to focus and the LED light. Before the camera permission is granted, a permission screen
 * covers it; when the camera can't start, an error panel does.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val statePrefs by lazy { getSharedPreferences(STATE_PREFS, MODE_PRIVATE) }

    @VisibleForTesting
    internal var permissionScreen: PermissionScreen? = null
        private set

    @VisibleForTesting
    internal var problem: CameraProblem? = null
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraStarting = false
    private var hasFlashUnit: Boolean? = null
    private var torchState: Int? = null
    private var draggingSlider = false
    private var updatingSlider = false
    private var pendingTap: PointF? = null
    private var controlsPadding = IntArray(4)
    private var panelPadding = IntArray(4)

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.permissionTitle, true)
        applyWindowInsets()
        setUpControls()
        setUpPreviewGestures()
        renderZoom()
        renderTorch()
    }

    override fun onStart() {
        super.onStart()
        // Also after returning from the app's settings, where the permission may have changed.
        refreshPermission()
    }

    // ---- Permission ----------------------------------------------------------------------------

    private fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val screen = PermissionScreen.from(
            granted = granted,
            askedBefore = statePrefs.getBoolean(KEY_ASKED_CAMERA, false),
            showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA),
        )
        permissionScreen = screen
        with(binding) {
            permissionPanel.isVisible = screen != PermissionScreen.CAMERA
            if (screen != PermissionScreen.CAMERA) {
                permissionTitle.setText(screen.title)
                permissionBody.setText(screen.body)
                permissionButton.setText(screen.button)
            }
        }
        renderCameraArea()
        if (screen == PermissionScreen.CAMERA) startCamera()
    }

    private fun onPermissionButton() {
        when (permissionScreen) {
            PermissionScreen.EXPLAIN, PermissionScreen.DENIED -> {
                statePrefs.edit { putBoolean(KEY_ASKED_CAMERA, true) }
                requestCamera.launch(Manifest.permission.CAMERA)
            }
            PermissionScreen.BLOCKED -> openAppSettings()
            PermissionScreen.CAMERA, null -> Unit
        }
    }

    private fun openAppSettings() {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(details)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Nothing to open; the screen keeps telling the user what to do.
            }
        }
    }

    // ---- Camera --------------------------------------------------------------------------------

    private fun startCamera() {
        if (!cameraStartEnabled) return
        if (camera != null || cameraStarting || problem == CameraProblem.NO_REAR_CAMERA) return
        cameraStarting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (isFinishing || isDestroyed) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                debugLog("CameraX failed to start", e)
                showProblem(CameraProblem.OTHER)
                return@addListener
            }
            cameraProvider = provider
            bindCamera(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val hasRearCamera = try {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (_: CameraInfoUnavailableException) {
            false
        }
        if (!hasRearCamera) {
            showProblem(CameraProblem.NO_REAR_CAMERA)
            return
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(binding.previewView.surfaceProvider)
        val bound = try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        } catch (e: Exception) {
            debugLog("binding the camera failed", e)
            showProblem(CameraProblem.OTHER)
            return
        }
        camera = bound
        hasFlashUnit = bound.cameraInfo.hasFlashUnit()
        bound.cameraInfo.zoomState.observe(this) { renderZoom() }
        bound.cameraInfo.torchState.observe(this) { state ->
            torchState = state
            renderTorch()
        }
        bound.cameraInfo.cameraState.observe(this) { onCameraState(it) }
        renderZoom()
        renderTorch()
    }

    private fun onCameraState(state: CameraState) {
        val error = state.error
        when {
            error != null -> {
                debugLog("camera state ${state.type}, error ${error.code}", error.cause)
                showProblem(CameraProblem.fromCameraStateError(error.code))
            }
            state.type == CameraState.Type.OPEN -> showProblem(null)
        }
    }

    /** "Try again": drops the camera and binds it afresh. */
    private fun retryCamera() {
        camera?.cameraInfo?.let { info ->
            info.zoomState.removeObservers(this)
            info.torchState.removeObservers(this)
            info.cameraState.removeObservers(this)
        }
        camera = null
        hasFlashUnit = null
        torchState = null
        showProblem(null)
        renderZoom()
        renderTorch()
        val provider = cameraProvider
        if (provider != null) bindCamera(provider) else startCamera()
    }

    private fun showProblem(newProblem: CameraProblem?) {
        problem = newProblem
        with(binding) {
            if (newProblem != null) {
                errorText.setText(newProblem.message)
                errorRetry.isVisible = newProblem.canRetry
            }
        }
        renderCameraArea()
    }

    /** Shows the controls or the error panel, and keeps the screen on, only while the camera can show. */
    private fun renderCameraArea() {
        val granted = permissionScreen == PermissionScreen.CAMERA
        val cameraShowing = granted && problem == null
        binding.controls.isVisible = cameraShowing
        binding.errorPanel.isVisible = granted && problem != null
        if (cameraShowing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** True while the live camera is in front, with no permission or error screen over it. */
    @VisibleForTesting
    internal fun cameraInFront(): Boolean =
        permissionScreen == PermissionScreen.CAMERA && problem == null && camera != null && !isFinishing

    private fun currentZoom(): ZoomState? = camera?.cameraInfo?.zoomState?.value

    // ---- Controls ------------------------------------------------------------------------------

    private fun setUpControls() = with(binding) {
        permissionButton.setOnClickListener { onPermissionButton() }
        errorRetry.setOnClickListener { retryCamera() }
        lightButton.setOnClickListener {
            val state = TorchButtonState.from(hasFlashUnit, torchState)
            val cam = camera ?: return@setOnClickListener
            if (!state.enabled) return@setOnClickListener
            val result = cam.cameraControl.enableTorch(state.tapTurnsOn)
            result.addListener({
                try {
                    result.get()
                } catch (e: Exception) {
                    debugLog("torch change failed", e)
                }
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }
        zoomIn.setOnClickListener { zoomBySteps(1) }
        zoomOut.setOnClickListener { zoomBySteps(-1) }

        zoomSlider.valueFrom = 0f
        zoomSlider.valueTo = ZoomMath.SLIDER_MAX
        zoomSlider.addOnChangeListener { _, value, _ ->
            // Any change the app didn't make itself is the user's: touch, keyboard or TalkBack.
            if (!updatingSlider) camera?.cameraControl?.setLinearZoom(ZoomMath.sliderToLinear(value))
        }
        zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingSlider = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingSlider = false
                renderZoom()
            }
        })
        // What TalkBack says for the slider's value: the zoom, not a percentage.
        zoomSlider.setLabelFormatter { value ->
            val zoom = currentZoom() ?: return@setLabelFormatter ""
            val ratio = ZoomMath.linearToRatio(ZoomMath.sliderToLinear(value), zoom.minZoomRatio, zoom.maxZoomRatio)
            getString(R.string.zoom_spoken, ZoomMath.formatRatio(ratio, resources.configuration.locales[0]))
        }
    }

    private fun zoomBySteps(steps: Int) {
        val zoom = currentZoom() ?: return
        if (!ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)) return
        val target = ZoomMath.stepLinear(zoom.linearZoom, steps)
        camera?.cameraControl?.setLinearZoom(target)
    }

    private fun renderZoom() = with(binding) {
        val zoom = currentZoom()
        val canZoom = zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)
        zoomSlider.isEnabled = canZoom
        zoomIn.isEnabled = canZoom && zoom.linearZoom < 1f
        zoomOut.isEnabled = canZoom && zoom.linearZoom > 0f
        if (zoom == null) {
            zoomLabel.text = ""
            zoomLabel.contentDescription = null
            return@with
        }
        if (!draggingSlider) {
            updatingSlider = true
            zoomSlider.value = ZoomMath.linearToSlider(zoom.linearZoom)
            updatingSlider = false
        }
        if (canZoom) {
            val ratio = ZoomMath.formatRatio(zoom.zoomRatio, resources.configuration.locales[0])
            zoomLabel.text = getString(R.string.zoom_label, ratio)
            zoomLabel.contentDescription = getString(R.string.zoom_spoken, ratio)
        } else {
            zoomLabel.setText(R.string.zoom_unavailable)
            zoomLabel.contentDescription = null
        }
    }

    private fun renderTorch() = with(binding.lightButton) {
        val state = TorchButtonState.from(hasFlashUnit, torchState)
        isEnabled = state.enabled
        setText(state.text)
        setIconResource(state.icon)
        contentDescription = getString(state.contentDescription)
        ViewCompat.setStateDescription(
            this,
            if (state.stateDescription != 0) getString(state.stateDescription) else null,
        )
        val background = ContextCompat.getColor(context, if (state.isOn) R.color.accent else R.color.control_background)
        val foreground = ContextCompat.getColor(context, if (state.isOn) R.color.on_accent else R.color.control_foreground)
        backgroundTintList = ColorStateList.valueOf(background)
        setTextColor(foreground)
        iconTint = ColorStateList.valueOf(foreground)
        alpha = if (state.enabled) 1f else DISABLED_ALPHA
    }

    // ---- Preview gestures: pinch to zoom, tap to focus -----------------------------------------

    private fun setUpPreviewGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoom = currentZoom() ?: return true
                if (!ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)) return true
                camera?.cameraControl?.setZoomRatio(
                    ZoomMath.pinchRatio(zoom.zoomRatio, detector.scaleFactor, zoom.minZoomRatio, zoom.maxZoomRatio),
                )
                return true
            }
        })
        // GestureDetector cancels a tap by itself when a second finger comes down, so a pinch never
        // also focuses.
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                pendingTap = PointF(e.x, e.y)
                return true
            }
        })
        binding.previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && pendingTap != null) view.performClick()
            true
        }
        // A tap (above) focuses where the finger was; a click from TalkBack or a keyboard focuses
        // the middle of the picture.
        binding.previewView.setOnClickListener { view ->
            val point = pendingTap ?: PointF(view.width / 2f, view.height / 2f)
            pendingTap = null
            focusAt(point.x, point.y)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (!cameraInFront()) return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        // Focus and exposure at the point; after FOCUS_RESET_SECONDS CameraX cancels it and the
        // camera returns to continuous autofocus.
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_RESET_SECONDS, TimeUnit.SECONDS)
            .build()
        binding.focusRing.show(x, y)
        val result = cam.cameraControl.startFocusAndMetering(action)
        result.addListener({
            val focused = try {
                result.get().isFocusSuccessful
            } catch (e: Exception) {
                debugLog("tap to focus failed", e)
                false
            }
            debugLog("tap to focus at (${x.toInt()}, ${y.toInt()}): focused=$focused")
            binding.focusRing.showResult(focused)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Volume keys ---------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val zoom = currentZoom()
        val result = VolumeKeyZoom.onKeyDown(
            keyCode = keyCode,
            cameraInFront = cameraInFront(),
            currentLinear = zoom?.linearZoom,
            canZoom = zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio),
        )
        if (result.consumed) {
            result.newLinear?.let { camera?.cameraControl?.setLinearZoom(it) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val zoom = currentZoom()
        val consumed = VolumeKeyZoom.consumesKeyUp(
            keyCode = keyCode,
            cameraInFront = cameraInFront(),
            currentLinear = zoom?.linearZoom,
            canZoom = zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio),
        )
        return consumed || super.onKeyUp(keyCode, event)
    }

    // ---- Layout --------------------------------------------------------------------------------

    /** Edge to edge: the picture fills the screen; the controls and panels keep clear of the bars. */
    private fun applyWindowInsets() = with(binding) {
        controlsPadding = intArrayOf(controls.paddingLeft, controls.paddingTop, controls.paddingRight, controls.paddingBottom)
        panelPadding = intArrayOf(
            permissionPanel.paddingLeft, permissionPanel.paddingTop,
            permissionPanel.paddingRight, permissionPanel.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controls.updatePadding(
                left = controlsPadding[0] + bars.left,
                right = controlsPadding[2] + bars.right,
                bottom = controlsPadding[3] + bars.bottom,
            )
            permissionPanel.updatePadding(
                left = panelPadding[0] + bars.left,
                top = panelPadding[1] + bars.top,
                right = panelPadding[2] + bars.right,
                bottom = panelPadding[3] + bars.bottom,
            )
            insets
        }
    }

    private fun debugLog(message: String, error: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.d(TAG, message, error)
    }

    internal companion object {
        const val TAG = "Magnifier"

        /** Device-only state, never backed up (see res/xml/backup_rules.xml). */
        const val STATE_PREFS = "magnifier_state"
        const val KEY_ASKED_CAMERA = "asked_camera_permission"

        const val FOCUS_RESET_SECONDS = 5L

        /** Unit tests turn this off: Robolectric has no camera for CameraX to open. */
        @VisibleForTesting
        internal var cameraStartEnabled = true
        private const val DISABLED_ALPHA = 0.5f
    }
}
