package bukin.magnifier

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
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
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import bukin.magnifier.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs

/**
 * The magnifier: the rear camera full screen, with zoom (slider, pinch, volume keys, buttons),
 * focus (auto, tap to lock, and a near-to-far slider where the camera supports it) and the LED
 * light. Before the camera permission is granted, a permission screen covers it; when the camera
 * can't start, an error panel does.
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

    @VisibleForTesting
    internal var focusMode = FocusMode.AUTO
        private set

    /** The focus slider's range, or null when the camera has no manual focus (the slider is hidden). */
    @VisibleForTesting
    @Volatile
    internal var manualFocus: ManualFocus? = null

    /**
     * Set when the app leaves the screen. The camera is already closing then, so it can't take
     * the return to Auto; it gets it again when it reopens.
     */
    private var resetFocusOnOpen = false

    /** Counts focus changes, so a slow camera reply to an older one is ignored. */
    private var focusGeneration = 0
    private var updatingFocusSlider = false
    private var draggingFocusSlider = false

    /** The lens's focus distance in the latest frame (diopters), written on a camera thread. */
    @Volatile
    private var lensDiopters = Float.NaN
    private var lastFocusSync = 0L
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
        renderFocus()
    }

    override fun onStart() {
        super.onStart()
        // Also after returning from the app's settings, where the permission may have changed.
        refreshPermission()
    }

    override fun onStop() {
        // Leaving the app ends a focus lock or manual focus: the next look starts in Auto.
        onFocusEvent(FocusEvent.LEFT_APP)
        super.onStop()
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

    // FEATURE_CAMERA is the rear camera, which is exactly what the magnifier needs (lint suggests
    // FEATURE_CAMERA_ANY for ChromeOS, which would also accept a front-only device).
    @SuppressLint("UnsupportedChromeOsCameraSystemFeature")
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
                // CameraX is limited to the rear camera (MagnifierApp), so a phone without one
                // fails here rather than at bindCamera.
                debugLog("CameraX failed to start", e)
                val hasRear = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
                showProblem(if (hasRear) CameraProblem.OTHER else CameraProblem.NO_REAR_CAMERA)
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
        val preview = buildPreview()
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
        manualFocus = readManualFocus(bound.cameraInfo)
        debugLog("manual focus: $manualFocus")
        bound.cameraInfo.zoomState.observe(this) { renderZoom() }
        bound.cameraInfo.torchState.observe(this) { state ->
            torchState = state
            renderTorch()
        }
        bound.cameraInfo.cameraState.observe(this) { onCameraState(it) }
        renderZoom()
        renderTorch()
        renderFocus()
    }

    /** The preview, with a listener that reads each frame's lens focus distance. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildPreview(): Preview {
        val builder = Preview.Builder()
        Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) = onFrameResult(result)
        })
        return builder.build()
    }

    /**
     * Manual focus for the bound camera: only when it officially supports it (see
     * [ManualFocus.check]).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readManualFocus(info: CameraInfo): ManualFocus? = try {
        val id = Camera2CameraInfo.from(info).cameraId
        ManualFocus.from(getSystemService(CameraManager::class.java).getCameraCharacteristics(id))
    } catch (e: Exception) {
        debugLog("reading the focus characteristics failed", e)
        null
    }

    private fun onCameraState(state: CameraState) {
        val error = state.error
        when {
            error != null -> {
                debugLog("camera state ${state.type}, error ${error.code}", error.cause)
                showProblem(CameraProblem.fromCameraStateError(error.code))
            }
            state.type == CameraState.Type.OPEN -> {
                showProblem(null)
                if (resetFocusOnOpen) {
                    resetFocusOnOpen = false
                    // Manual focus options outlive a camera close: clear them now it's open.
                    if (focusMode == FocusMode.AUTO) camera?.let { backToAuto(it) }
                }
            }
        }
    }

    /** "Try again": drops the camera and binds it afresh. */
    private fun retryCamera() {
        onFocusEvent(FocusEvent.CAMERA_RESTARTED)
        camera?.cameraInfo?.let { info ->
            info.zoomState.removeObservers(this)
            info.torchState.removeObservers(this)
            info.cameraState.removeObservers(this)
        }
        camera = null
        hasFlashUnit = null
        torchState = null
        manualFocus = null
        showProblem(null)
        renderZoom()
        renderTorch()
        renderFocus()
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
        focusButton.setOnClickListener { onFocusEvent(FocusEvent.FOCUS_BUTTON) }
        setUpFocusSlider()

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

    private fun setUpFocusSlider() = with(binding) {
        focusSlider.valueFrom = 0f
        focusSlider.valueTo = ManualFocus.SLIDER_MAX
        focusSlider.value = ManualFocus.SLIDER_MAX
        focusSlider.addOnChangeListener { _, _, _ ->
            // As with zoom: any change the app didn't make itself is the user's.
            if (!updatingFocusSlider) onFocusEvent(FocusEvent.FOCUS_SLIDER)
        }
        focusSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingFocusSlider = true
                // Touching the slider takes over, even where its value doesn't change.
                onFocusEvent(FocusEvent.FOCUS_SLIDER)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingFocusSlider = false
            }
        })
        focusNear.setOnClickListener { stepFocus(-1) }
        focusFar.setOnClickListener { stepFocus(1) }
        focusSlider.setLabelFormatter { value -> spokenFocus(value) }
    }

    /** The near / far buttons. Also at an end of the slider, they take over the focus. */
    private fun stepFocus(steps: Int) = with(binding.focusSlider) {
        val target = ManualFocus.step(value, steps)
        if (target != value) value = target else onFocusEvent(FocusEvent.FOCUS_SLIDER)
    }

    private fun spokenFocus(value: Float): String {
        val range = manualFocus ?: return ""
        return when (val distance = range.describe(value)) {
            ManualFocus.Distance.Far -> getString(R.string.focus_spoken_far)
            is ManualFocus.Distance.Centimetres ->
                resources.getQuantityString(R.plurals.focus_spoken_cm, distance.cm, distance.cm)
            is ManualFocus.Distance.Metres ->
                getString(R.string.focus_spoken_m, ZoomMath.formatRatio(distance.m, resources.configuration.locales[0]))
            is ManualFocus.Distance.Percent -> getString(R.string.focus_spoken_percent, distance.percent)
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
        paintStateButton(this, highlighted = state.isOn)
        alpha = if (state.enabled) 1f else DISABLED_ALPHA
    }

    /** The light and focus buttons: accent colour when they're not in their default state. */
    private fun paintStateButton(button: MaterialButton, highlighted: Boolean) {
        val context = button.context
        val background = ContextCompat.getColor(context, if (highlighted) R.color.accent else R.color.control_background)
        val foreground = ContextCompat.getColor(context, if (highlighted) R.color.on_accent else R.color.control_foreground)
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.setTextColor(foreground)
        button.iconTint = ColorStateList.valueOf(foreground)
    }

    private fun renderFocus() = with(binding) {
        val mode = focusMode
        val ready = camera != null
        focusButton.isEnabled = ready
        focusButton.setText(mode.text)
        focusButton.setIconResource(mode.icon)
        ViewCompat.setStateDescription(focusButton, getString(mode.text))
        ViewCompat.replaceAccessibilityAction(
            focusButton, AccessibilityActionCompat.ACTION_CLICK, getString(mode.buttonAction), null,
        )
        paintStateButton(focusButton, mode.highlighted)
        focusButton.alpha = if (ready) 1f else DISABLED_ALPHA
        focusRow.isVisible = manualFocus != null
        focusSlider.isEnabled = ready
        focusNear.isEnabled = ready && focusSlider.value > 0f
        focusFar.isEnabled = ready && focusSlider.value < ManualFocus.SLIDER_MAX
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
        // A tap (above) focuses where the finger was and locks the focus there; a click from
        // TalkBack or a keyboard does it for the middle of the picture.
        binding.previewView.setOnClickListener { view ->
            val point = pendingTap ?: PointF(view.width / 2f, view.height / 2f)
            pendingTap = null
            if (cameraInFront()) onFocusEvent(FocusEvent.TAP_PICTURE, point)
        }
    }

    // ---- Focus: Auto, Locked (tap), Manual (slider) ---------------------------------------------

    @VisibleForTesting
    internal fun onFocusEvent(event: FocusEvent, at: PointF? = null) {
        val old = focusMode
        val new = old.next(event, manualSupported = manualFocus != null)
        focusMode = new
        val generation = ++focusGeneration
        if (event == FocusEvent.LEFT_APP) resetFocusOnOpen = true
        val cam = camera
        if (cam != null) {
            when (new) {
                FocusMode.AUTO -> backToAuto(cam)
                FocusMode.LOCKED -> {
                    val point = at ?: PointF(binding.previewView.width / 2f, binding.previewView.height / 2f)
                    // Manual focus's AF_MODE_OFF would override the tap's autofocus: clear it first.
                    if (old == FocusMode.MANUAL) {
                        clearManualFocus(cam).addListener({
                            if (generation == focusGeneration) lockFocusAt(cam, point)
                        }, ContextCompat.getMainExecutor(this))
                    } else {
                        lockFocusAt(cam, point)
                    }
                }
                FocusMode.MANUAL -> {
                    if (old == FocusMode.LOCKED) cam.cameraControl.cancelFocusAndMetering().logFailure("cancel focus")
                    setManualFocus(cam, binding.focusSlider.value)
                }
            }
        }
        if (new != old) debugLog("focus $old -> $new ($event)")
        renderFocus()
    }

    /** Continuous autofocus again: no tap lock, no manual distance. */
    private fun backToAuto(cam: Camera) {
        // Both always: the camera ignores what isn't set, and LEFT_APP may come while it closes.
        clearManualFocus(cam)
        cam.cameraControl.cancelFocusAndMetering().logFailure("cancel focus")
    }

    /** Focus and meter at [x], [y] once, then keep the lens there: no auto-cancel. */
    private fun lockFocusAt(cam: Camera, point: PointF) {
        if (!cameraInFront()) return
        val meteringPoint = binding.previewView.meteringPointFactory.createPoint(point.x, point.y)
        val action = FocusMeteringAction.Builder(
            meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).disableAutoCancel().build()
        val tap = binding.focusRing.show(point.x, point.y)
        val result = cam.cameraControl.startFocusAndMetering(action)
        result.addListener({
            val focused = try {
                result.get().isFocusSuccessful
            } catch (e: Exception) {
                debugLog("tap to focus failed", e)
                false
            }
            debugLog("focus locked at (${point.x.toInt()}, ${point.y.toInt()}): focused=$focused")
            binding.focusRing.showResult(tap, focused)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Autofocus off, and the lens at the slider's distance. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setManualFocus(cam: Camera, sliderValue: Float) {
        val range = manualFocus ?: return
        val diopters = range.sliderToDiopters(sliderValue)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            .build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options).logFailure("manual focus")
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun clearManualFocus(cam: Camera): ListenableFuture<Void?> =
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions().also { it.logFailure("clear manual focus") }

    /**
     * Each frame's result, on a camera thread. Outside Manual, the focus slider follows where
     * autofocus put the lens, so taking it over doesn't jump the focus.
     */
    private fun onFrameResult(result: TotalCaptureResult) {
        val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
        val previous = lensDiopters
        lensDiopters = diopters
        if (manualFocus == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusSync < FOCUS_SYNC_MS || abs(diopters - previous) < FOCUS_SYNC_DIOPTERS) return
        lastFocusSync = now
        binding.focusSlider.post { syncFocusSliderToLens() }
    }

    private fun syncFocusSliderToLens() {
        val range = manualFocus ?: return
        val diopters = lensDiopters
        if (focusMode == FocusMode.MANUAL || draggingFocusSlider || diopters.isNaN()) return
        updatingFocusSlider = true
        binding.focusSlider.value = range.dioptersToSlider(diopters)
        updatingFocusSlider = false
        renderFocus()
    }

    private fun <T> ListenableFuture<T>.logFailure(what: String) {
        if (!BuildConfig.DEBUG) return
        addListener({
            try {
                get()
            } catch (e: Exception) {
                // Expected and harmless: a newer change replaced this one, or the camera closed.
                if (e.cause !is CameraControl.OperationCanceledException) debugLog("$what failed", e)
            }
        }, ContextCompat.getMainExecutor(this@MainActivity))
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

        /** The focus slider follows the lens at most this often, and only for a change this big. */
        private const val FOCUS_SYNC_MS = 250L
        private const val FOCUS_SYNC_DIOPTERS = 0.05f

        /** Unit tests turn this off: Robolectric has no camera for CameraX to open. */
        @VisibleForTesting
        internal var cameraStartEnabled = true
        private const val DISABLED_ALPHA = 0.5f
    }
}
