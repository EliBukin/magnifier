package bukin.magnifier

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
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
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.exifinterface.media.ExifInterface
import bukin.magnifier.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The magnifier: the rear camera full screen, with zoom (the lens ring, pinch, volume keys),
 * focus (auto, tap to lock, and a near-to-far focus ring where the camera supports it), the LED
 * light, colour modes and brightness. The lens ring adjusts one of zoom, brightness and focus. Freeze holds a sharp photo to zoom and move around, save
 * or share. Before the camera permission is granted, a permission screen covers it; when the
 * camera can't start, an error panel does.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val statePrefs by lazy { getSharedPreferences(STATE_PREFS, MODE_PRIVATE) }
    private val settings by lazy { MagnifierSettings(this) }

    /** Photo decoding and picture export, off the main thread, one job at a time. */
    private lateinit var worker: ExecutorService

    @VisibleForTesting
    internal var permissionScreen: PermissionScreen? = null
        private set

    @VisibleForTesting
    internal var problem: CameraProblem? = null
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraStarting = false
    private var hasFlashUnit: Boolean? = null
    private var torchState: Int? = null
    private var pendingTap: PointF? = null

    /** What the lens ring adjusts: zoom, unless the user chose brightness or focus. Not remembered. */
    @VisibleForTesting
    internal var ringMode = RingMode.ZOOM
        private set

    /** The zoom the user last chose (CameraX linear zoom): remembered, and set again when the camera opens. */
    @VisibleForTesting
    internal var wantedLinearZoom = 0f
        private set

    @VisibleForTesting
    internal var focusMode = FocusMode.AUTO
        private set

    /** The focus ring's range, or null when the camera has no manual focus (no focus ring). */
    @VisibleForTesting
    @Volatile
    internal var manualFocus: ManualFocus? = null

    /** Checks that the camera really applies manual focus; null without manual focus. */
    @Volatile
    private var focusGuard: ManualFocusGuard? = null
    private val focusGuardTimeout = Runnable { checkFocusGuardTimeout() }

    /** Set while a tap after manual focus waits for continuous autofocus to settle. */
    @Volatile
    private var afSettle: AfSettle? = null
    private var afSettleLock: (() -> Unit)? = null
    private val afSettleTimeout = Runnable {
        afSettle?.let { if (it.onTimeout(SystemClock.elapsedRealtime())) onAfSettled(it) }
    }

    /**
     * Set when the app leaves the screen. The camera is already closing then, so it can't take
     * the return to Auto; it gets it again when it reopens.
     */
    private var resetFocusOnOpen = false

    /** Counts focus changes, so a slow camera reply to an older one is ignored. */
    private var focusGeneration = 0

    /**
     * The focus ring's place, near (0) to far ([ManualFocus.SLIDER_MAX]): the lens distance in
     * Manual. Outside Manual it follows the lens, so taking over doesn't make the focus jump.
     */
    @VisibleForTesting
    internal var focusValue = ManualFocus.SLIDER_MAX
        private set

    /** The lens's focus distance in the latest frame (diopters), written on a camera thread. */
    @Volatile
    private var lensDiopters = Float.NaN
    private var lastFocusSync = 0L
    private var controlsPadding = IntArray(4)
    private var panelPadding = IntArray(4)
    private var topMargins = IntArray(2)

    // ---- Colours and brightness ----------------------------------------------------------------

    @VisibleForTesting
    internal var colourMode = ColorMode.NORMAL
        private set

    /** The camera's exposure range for live brightness, or null when the camera has none. */
    @VisibleForTesting
    internal var exposure: Brightness.Exposure? = null

    /**
     * The live brightness on the camera's exposure grid, in the screen's sense: + is a lighter
     * screen in every colour mode (see [Brightness.Exposure.cameraIndex]). Read from the camera
     * when it opens and when the picture goes back to live.
     */
    @VisibleForTesting
    internal var brightnessValue = 0f
        private set

    /**
     * The frozen picture's brightness, -10..10, + lighter on screen in every mode (the gain
     * is [Brightness.gain] of [ColorMode.lightFor] it); 0 at every freeze.
     */
    @VisibleForTesting
    internal var frozenLevel = 0
        private set

    // ---- Freeze --------------------------------------------------------------------------------

    /** A picture the frozen view can show, save and share: [rotation] stands it up. */
    private class Picture(val bitmap: Bitmap, val rotation: Int)

    @VisibleForTesting
    internal var frozen = false
        private set

    /** Counts freezes, so a photo arriving after "Live" (or a newer freeze) is dropped. */
    private var freezeGeneration = 0
    private var placeholder: Bitmap? = null

    /** What Save and Share use: the sharp photo, or the screen's picture if the photo failed. */
    private var frozenPicture: Picture? = null
    private var cameraZoomAtFreeze = 1f
    private var freezeStartMs = 0L

    /** True while a picture is being saved or shared. */
    private var exporting = false
    private val recycleAfterExport = mutableListOf<Bitmap>()

    /** The JPEG last written for this freeze, colour mode and brightness: saved or shared again as it is. */
    private var lastExport: Pair<ExportKey, File>? = null
    private data class ExportKey(val freeze: Int, val mode: ColorMode, val level: Int)
    private enum class ExportKind { SAVE, SHARE }
    private var pendingSave = false

    private val hideMessage = Runnable {
        binding.messageView.animate().alpha(0f).setDuration(MESSAGE_FADE_MS).withEndAction { binding.messageView.isVisible = false }
    }

    /** Back while frozen goes back to the live picture, like the Live button; otherwise it leaves as usual. */
    private val backToLive = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = unfreeze()
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermission() }

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onStoragePermission(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        worker = Executors.newSingleThreadExecutor()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.permissionTitle, true)
        colourMode = settings.colourMode
        wantedLinearZoom = settings.zoomLinear
        applyWindowInsets()
        setUpControls()
        setUpPreviewGestures()
        binding.frozenView.onScaleChanged = { renderRing() }
        // A pinch or double tap on the frozen picture shows the zoom on the ring.
        binding.frozenView.onUserZoom = { if (ringMode != RingMode.ZOOM) setRingMode(RingMode.ZOOM) }
        onBackPressedDispatcher.addCallback(this, backToLive)
        applyPreviewFilter()
        renderTorch()
        renderColour()
        renderFreeze()
        // Pictures shared on earlier visits: the apps they went to have had them for a while now.
        val shareDir = PictureStore.shareDir(this)
        worker.execute {
            val deleted = PictureStore.cleanShareDir(shareDir, System.currentTimeMillis())
            if (deleted > 0) debugLog("deleted $deleted old shared pictures")
        }
    }

    override fun onStart() {
        super.onStart()
        // Also after returning from the app's settings, where the permission may have changed.
        refreshPermission()
    }

    override fun onStop() {
        // Leaving the app ends a focus lock or manual focus: the next look starts in Auto.
        onFocusEvent(FocusEvent.LEFT_APP)
        binding.root.removeCallbacks(saveZoom)
        settings.zoomLinear = wantedLinearZoom
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
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
            // The view port (the part of the camera's picture the screen shows) needs the view's size.
            binding.previewView.doOnLayout { if (camera == null && !isDestroyed) bindCamera(provider) }
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
        val capture = buildImageCapture()
        // One view port for both: the photo then knows which part of it the screen showed, so the
        // frozen picture starts exactly like the live one.
        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
        binding.previewView.viewPort?.let { group.setViewPort(it) }
        val bound = try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build()).also { imageCapture = capture }
        } catch (e: Exception) {
            // A camera that can't take photos beside the preview still magnifies; Freeze then
            // holds the screen's picture.
            debugLog("binding the camera with photos failed, trying the preview alone", e)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview).also { imageCapture = null }
            } catch (e2: Exception) {
                debugLog("binding the camera failed", e2)
                showProblem(CameraProblem.OTHER)
                return
            }
        }
        camera = bound
        hasFlashUnit = bound.cameraInfo.hasFlashUnit()
        manualFocus = readManualFocus(bound.cameraInfo)
        focusGuard = manualFocus?.let { ManualFocusGuard(it.minimumFocusDiopters) }
        debugLog("manual focus: $manualFocus; photos: ${imageCapture?.resolutionInfo?.resolution}")
        bound.cameraInfo.zoomState.observe(this) { renderRing() }
        bound.cameraInfo.torchState.observe(this) { state ->
            torchState = state
            renderTorch()
        }
        bound.cameraInfo.cameraState.observe(this) { onCameraState(it) }
        readExposure()
        syncBrightnessFromCamera()
        renderTorch()
        renderFreeze()
    }

    /** The preview, with a listener that reads each frame's focus. */
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
     * Photos for Freeze: the largest 4:3 size up to about 16 megapixels (the A20's 13 MP in
     * full), quick rather than multi-frame, and never with the flash, so the light stays as the
     * user set it.
     */
    private fun buildImageCapture(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(PHOTO_MAX_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build(),
        )
        .build()

    /**
     * Manual focus for the bound camera, from its characteristics (see [ManualFocus.check]);
     * [ManualFocusGuard] then checks that the camera really applies it.
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
                val cam = camera ?: return
                if (resetFocusOnOpen) {
                    resetFocusOnOpen = false
                    // Manual focus options outlive a camera close: clear them now it's open.
                    if (focusMode == FocusMode.AUTO) backToAuto(cam)
                }
                restoreSettings(cam)
                readExposure()
                syncBrightnessFromCamera()
                renderFreeze()
            }
        }
    }

    /** The remembered zoom, and the light if the user left it on. */
    private fun restoreSettings(cam: Camera) {
        val zoom = currentZoom()
        if (zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio) &&
            abs(zoom.linearZoom - wantedLinearZoom) > 1e-3f
        ) {
            cam.cameraControl.setLinearZoom(wantedLinearZoom).logFailure("restore zoom")
        }
        if (settings.lightOn && hasFlashUnit == true && torchState != TorchState.ON) {
            cam.cameraControl.enableTorch(true).logFailure("restore light")
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
        imageCapture = null
        hasFlashUnit = null
        torchState = null
        manualFocus = null
        focusGuard = null
        exposure = null
        binding.root.removeCallbacks(focusGuardTimeout)
        showProblem(null)
        renderTorch()
        renderFreeze()
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
        if (::binding.isInitialized) renderFreezeButton()
    }

    /**
     * Shows the controls or the error panel, and keeps the screen on, only while there is a
     * picture to look at. A frozen picture stays even if the camera has a problem meanwhile; the
     * problem shows after "Live".
     */
    private fun renderCameraArea() {
        val granted = permissionScreen == PermissionScreen.CAMERA
        val pictureShowing = granted && (problem == null || frozen)
        binding.controls.isVisible = pictureShowing
        binding.errorPanel.isVisible = granted && problem != null && !frozen
        if (pictureShowing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** True while the live camera is in front, with no permission or error screen over it, and not frozen. */
    @VisibleForTesting
    internal fun cameraInFront(): Boolean =
        permissionScreen == PermissionScreen.CAMERA && problem == null && camera != null && !isFinishing && !frozen

    private fun currentZoom(): ZoomState? = camera?.cameraInfo?.zoomState?.value

    // ---- Controls ------------------------------------------------------------------------------

    private fun setUpControls() = with(binding) {
        permissionButton.setOnClickListener { onPermissionButton() }
        errorRetry.setOnClickListener { retryCamera() }
        lightButton.setOnClickListener { onLightButton() }
        brightnessButton.setOnClickListener { onBrightnessButton() }
        focusButton.setOnClickListener { onFocusButton() }
        freezeButton.setOnClickListener { if (frozen) unfreeze() else freeze() }
        colourButton.setOnClickListener { setColourMode(colourMode.next(), announce = true) }
        saveButton.setOnClickListener { onSaveButton() }
        shareButton.setOnClickListener { startExport(ExportKind.SHARE) }
        // Inside the ring: back to zoom. Clickable only while the ring adjusts something else.
        ringCentre.setOnClickListener { setRingMode(RingMode.ZOOM) }
        lensRing.listener = object : LensRingView.Listener {
            override fun onRingTouchStart() {
                // Touching the focus ring takes over, even where its value doesn't change.
                if (ringMode == RingMode.FOCUS) onFocusEvent(FocusEvent.FOCUS_SLIDER)
            }

            override fun onRingValue(value: Float) = onRingTurned(value)

            override fun onRingTouchEnd() = renderRing()

            override fun onRingStep(steps: Int) = onRingStepped(steps)
        }
    }

    /** The ring was turned to [value]: zoom, brightness or focus, with the old sliders' mappings. */
    @VisibleForTesting
    internal fun onRingTurned(value: Float) {
        when (ringMode) {
            RingMode.ZOOM -> if (frozen) {
                binding.frozenView.zoomTo(FrozenZoom.sliderToScale(value))
            } else {
                val linear = ZoomMath.sliderToLinear(value)
                camera?.cameraControl?.setLinearZoom(linear)
                rememberZoom(linear)
            }
            RingMode.BRIGHTNESS -> setBrightness(value)
            RingMode.FOCUS -> {
                focusValue = value.coerceIn(0f, ManualFocus.SLIDER_MAX)
                onFocusEvent(FocusEvent.FOCUS_SLIDER)
            }
        }
        renderRing()
    }

    /**
     * One step of the ring (a tap beside the thumb or on an end icon, an arrow key, TalkBack):
     * the old − / + buttons' steps.
     */
    @VisibleForTesting
    internal fun onRingStepped(steps: Int) {
        when (ringMode) {
            RingMode.ZOOM -> zoomBySteps(steps)
            RingMode.BRIGHTNESS -> stepBrightness(steps)
            RingMode.FOCUS -> stepFocus(steps)
        }
    }

    /** Puts [wanted] on the ring, if it can be there now (see [RingModes.allowed]). */
    @VisibleForTesting
    internal fun setRingMode(wanted: RingMode) {
        ringMode = wanted
        renderRing()
    }

    /** Live, brightness needs the camera's exposure control (assumed until the camera says). */
    private fun brightnessAvailable(): Boolean = frozen || exposure != null || camera == null

    private fun onBrightnessButton() {
        if (!brightnessAvailable()) {
            // A camera without exposure control: say why, and how to brighten anyway.
            showMessage(getString(R.string.brightness_unavailable), forTalkBack = true)
            return
        }
        setRingMode(RingModes.brightnessButton(ringMode))
    }

    private fun onFocusButton() {
        when (RingModes.focusButton(focusMode, ringMode, manualFocus != null)) {
            FocusButtonAction.LOCK_MIDDLE, FocusButtonAction.BACK_TO_AUTO -> onFocusEvent(FocusEvent.FOCUS_BUTTON)
            FocusButtonAction.RING_TO_FOCUS -> setRingMode(RingMode.FOCUS)
            FocusButtonAction.RING_TO_ZOOM -> setRingMode(RingMode.ZOOM)
        }
    }

    private fun onLightButton() {
        val state = TorchButtonState.from(hasFlashUnit, torchState)
        val cam = camera ?: return
        if (!state.enabled) return
        settings.lightOn = state.tapTurnsOn
        val result = cam.cameraControl.enableTorch(state.tapTurnsOn)
        result.addListener({
            try {
                result.get()
            } catch (e: Exception) {
                debugLog("torch change failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** One step of the focus ring towards far (+) or near (−). At an end it still takes over the focus. */
    private fun stepFocus(steps: Int) {
        focusValue = ManualFocus.step(focusValue, steps)
        onFocusEvent(FocusEvent.FOCUS_SLIDER)
    }

    private fun spokenFocus(value: Float): String {
        val range = manualFocus ?: return ""
        return when (val distance = range.describe(value)) {
            ManualFocus.Distance.Far -> getString(R.string.focus_spoken_far)
            is ManualFocus.Distance.Centimetres ->
                resources.getQuantityString(R.plurals.focus_spoken_cm, distance.cm, distance.cm)
            is ManualFocus.Distance.Metres ->
                getString(R.string.focus_spoken_m, ZoomMath.formatOneDecimal(distance.m, resources.configuration.locales[0]))
            is ManualFocus.Distance.Percent -> getString(R.string.focus_spoken_percent, distance.percent)
        }
    }

    /** The focus distance as the ring's centre shows it: "20 cm", "1.5 m", "Far". */
    private fun shortFocus(value: Float): String {
        val range = manualFocus ?: return ""
        return when (val distance = range.describe(value)) {
            ManualFocus.Distance.Far -> getString(R.string.focus_spoken_far)
            is ManualFocus.Distance.Centimetres -> getString(R.string.focus_short_cm, distance.cm)
            is ManualFocus.Distance.Metres ->
                getString(R.string.focus_short_m, ZoomMath.formatOneDecimal(distance.m, resources.configuration.locales[0]))
            is ManualFocus.Distance.Percent -> getString(R.string.focus_short_percent, distance.percent)
        }
    }

    /** Ring taps and the volume keys: the camera's zoom live, the picture's when frozen. */
    @VisibleForTesting
    internal fun zoomBySteps(steps: Int) {
        if (frozen) {
            val view = binding.frozenView
            view.zoomTo(FrozenZoom.step(view.scale, steps, view.minScale, view.maxScale))
            return
        }
        val zoom = currentZoom() ?: return
        if (!ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)) return
        val target = ZoomMath.stepLinear(zoom.linearZoom, steps)
        camera?.cameraControl?.setLinearZoom(target)
        rememberZoom(target)
    }

    /**
     * The zoom the user chose, saved half a second after the last change (turning the ring makes
     * many), so it survives the app being killed without a chance to save on the way out.
     */
    private fun rememberZoom(linear: Float) {
        wantedLinearZoom = linear
        binding.root.removeCallbacks(saveZoom)
        binding.root.postDelayed(saveZoom, ZOOM_SAVE_DELAY_MS)
    }

    private val saveZoom = Runnable { settings.zoomLinear = wantedLinearZoom }

    private fun formatZoom(ratio: Float): String = ZoomMath.formatRatio(ratio, resources.configuration.locales[0])

    /** The zoom a ring position stands for, live or frozen; null without a camera. */
    private fun zoomForSlider(value: Float): Float? {
        if (frozen) {
            val view = binding.frozenView
            return cameraZoomAtFreeze * FrozenZoom.sliderToScale(value).coerceIn(view.minScale, view.maxScale)
        }
        val zoom = currentZoom() ?: return null
        return ZoomMath.linearToRatio(ZoomMath.sliderToLinear(value), zoom.minZoomRatio, zoom.maxZoomRatio)
    }

    // ---- The ring and its centre ---------------------------------------------------------------

    /** What the ring shows: its range, step grid, where the yellow starts, the value, the end icons. */
    private class RingSpec(
        val from: Float,
        val to: Float,
        val step: Float,
        val value: Float,
        @param:DrawableRes val start: Int,
        @param:DrawableRes val end: Int,
        val enabled: Boolean,
        /** Where the yellow part starts: the start, or 0 for brightness. */
        val fill: Float = from,
    )

    private fun ringSpec(): RingSpec = when (ringMode) {
        RingMode.ZOOM -> if (frozen) {
            val scale = binding.frozenView.scale
            RingSpec(0f, FrozenZoom.SLIDER_MAX, 0f, FrozenZoom.scaleToSlider(scale), R.drawable.ic_zoom_out, R.drawable.ic_zoom_in, true)
        } else {
            val zoom = currentZoom()
            val canZoom = zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)
            val linear = zoom?.linearZoom ?: wantedLinearZoom
            RingSpec(0f, ZoomMath.SLIDER_MAX, 0f, ZoomMath.linearToSlider(linear), R.drawable.ic_zoom_out, R.drawable.ic_zoom_in, canZoom)
        }
        RingMode.BRIGHTNESS -> {
            val range = exposure
            val levels = Brightness.FROZEN_LEVELS.toFloat()
            val (down, up) = R.drawable.ic_brightness_down to R.drawable.ic_brightness_up
            when {
                frozen -> RingSpec(-levels, levels, 1f, frozenLevel.toFloat(), down, up, true, fill = 0f)
                range != null -> RingSpec(
                    range.from.toFloat(), range.to.toFloat(), range.step.toFloat(), brightnessValue, down, up, camera != null, fill = 0f,
                )
                else -> RingSpec(-levels, levels, 1f, 0f, down, up, false, fill = 0f)
            }
        }
        RingMode.FOCUS -> RingSpec(0f, ManualFocus.SLIDER_MAX, 0f, focusValue, R.drawable.ic_focus_near, R.drawable.ic_focus_far, camera != null)
    }

    /**
     * The ring for what it adjusts now, its centre (what, and how much), and the two buttons that
     * choose it. Also when the zoom, the focus or the brightness changed some other way.
     */
    private fun renderRing() = with(binding) {
        ringMode = RingModes.allowed(ringMode, manualFocus != null, frozen, brightnessAvailable())
        val spec = ringSpec()
        // While a finger turns it, the ring keeps its own value (the camera reports zoom a little later).
        if (!lensRing.isDragging) lensRing.configure(spec.from, spec.to, spec.step, spec.fill, spec.value, spec.start, spec.end)
        lensRing.isEnabled = spec.enabled
        lensRing.contentDescription = getString(
            when (ringMode) {
                RingMode.ZOOM -> R.string.ring_zoom
                RingMode.BRIGHTNESS -> R.string.ring_brightness
                RingMode.FOCUS -> R.string.ring_focus_desc
            },
        )
        ViewCompat.setStateDescription(lensRing, ringState(lensRing.value))
        renderRingCentre()
        renderBrightnessButton()
        renderFocusButton()
    }

    /** What TalkBack says for the ring's value: "2.50 times", "3 steps lighter", "20 centimetres". */
    private fun ringState(value: Float): String? = when (ringMode) {
        RingMode.ZOOM -> zoomForSlider(value)?.let { getString(R.string.zoom_state, formatZoom(it)) }
        RingMode.BRIGHTNESS -> spokenBrightness(brightnessLevelAt(value))
        RingMode.FOCUS -> if (focusMode == FocusMode.MANUAL) spokenFocus(value) else getString(focusMode.text)
    }

    /**
     * Inside the ring: what it adjusts and the value, big ("2.50×", "+3", "20 cm", "Auto"). Away
     * from zoom it is a button back to zoom; on zoom, TalkBack hears every change ("Zoom 2.50 times").
     */
    private fun renderRingCentre() = with(binding) {
        val caption = getString(
            when (ringMode) {
                RingMode.ZOOM -> R.string.ring_zoom
                RingMode.BRIGHTNESS -> R.string.ring_brightness
                RingMode.FOCUS -> R.string.ring_focus
            },
        )
        val (shown, spoken) = when (ringMode) {
            RingMode.ZOOM -> zoomTexts()
            RingMode.BRIGHTNESS -> brightnessLevel().let { RingModes.levelText(it) to spokenBrightness(it) }
            RingMode.FOCUS -> when (focusMode) {
                FocusMode.MANUAL -> shortFocus(focusValue) to spokenFocus(focusValue)
                else -> getString(focusMode.text).let { it to it }
            }
        }
        ringCaption.setIfChanged(caption)
        ringValue.setIfChanged(shown)
        // Numbers read left to right in Hebrew too; a distance or "Auto" in the language's direction.
        ringValue.textDirection = if (ringMode == RingMode.FOCUS) View.TEXT_DIRECTION_LOCALE else View.TEXT_DIRECTION_LTR
        val toZoom = ringMode != RingMode.ZOOM
        ringCentre.isClickable = toZoom
        ringCentre.isFocusable = toZoom
        ringCentre.background = if (toZoom) ContextCompat.getDrawable(this@MainActivity, R.drawable.ring_centre_background) else null
        ringCentre.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ringCentre.contentDescription = when {
            spoken == null -> caption
            toZoom -> getString(R.string.ring_centre_spoken, caption, spoken)
            else -> spoken
        }
        ringCentre.accessibilityLiveRegion = if (toZoom) View.ACCESSIBILITY_LIVE_REGION_NONE else View.ACCESSIBILITY_LIVE_REGION_POLITE
        setClickLabel(ringCentre, if (toZoom) getString(R.string.ring_back_to_zoom) else null)
    }

    /** The zoom as the centre shows it ("2.50×") and as TalkBack says it ("Zoom 2.50 times"). */
    private fun zoomTexts(): Pair<String, String?> {
        val ratio = if (frozen) {
            cameraZoomAtFreeze * binding.frozenView.scale
        } else {
            val zoom = currentZoom() ?: return "" to null
            if (!ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)) {
                return getString(R.string.zoom_unavailable).let { it to it }
            }
            zoom.zoomRatio
        }
        val text = formatZoom(ratio)
        return getString(R.string.zoom_label, text) to getString(R.string.zoom_spoken, text)
    }

    /** The brightness button: the level ("+3"), yellow when on the ring or not at 0. */
    private fun renderBrightnessButton() = with(binding.brightnessButton) {
        val available = brightnessAvailable()
        val level = brightnessLevel()
        setIfChanged(RingModes.levelText(level))
        ViewCompat.setStateDescription(this, if (available) spokenBrightness(level) else getString(R.string.state_unavailable))
        setClickLabel(this, getString(if (ringMode == RingMode.BRIGHTNESS) R.string.ring_back_to_zoom else R.string.brightness_action_ring))
        paintStateButton(this, highlighted = ringMode == RingMode.BRIGHTNESS || level != 0)
        // Still tappable when unavailable: it says why.
        alpha = if (available && (camera != null || frozen)) 1f else DISABLED_ALPHA
    }

    /** The focus button: the mode, yellow when not Auto or when the focus is on the ring. */
    private fun renderFocusButton() = with(binding.focusButton) {
        val mode = focusMode
        val ready = camera != null
        isEnabled = ready
        setIfChanged(getString(mode.text))
        setIconIfChanged(this, mode.icon)
        ViewCompat.setStateDescription(this, getString(mode.text))
        val action = when (RingModes.focusButton(mode, ringMode, manualFocus != null)) {
            FocusButtonAction.LOCK_MIDDLE -> R.string.focus_action_lock
            FocusButtonAction.BACK_TO_AUTO -> R.string.focus_action_auto
            FocusButtonAction.RING_TO_FOCUS -> R.string.focus_action_ring
            FocusButtonAction.RING_TO_ZOOM -> R.string.ring_back_to_zoom
        }
        setClickLabel(this, getString(action))
        paintStateButton(this, mode.highlighted || ringMode == RingMode.FOCUS)
        alpha = if (ready) 1f else DISABLED_ALPHA
    }

    private fun android.widget.TextView.setIfChanged(value: CharSequence) {
        if (text?.toString() != value.toString()) text = value
    }

    /** Click labels already set, so renders don't send TalkBack a change each time. */
    private val clickLabels = HashMap<Int, String?>()

    private fun setClickLabel(view: View, label: String?) {
        if (clickLabels.containsKey(view.id) && clickLabels[view.id] == label) return
        clickLabels[view.id] = label
        ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_CLICK, label, null)
    }

    private val icons = HashMap<Int, Int>()

    private fun setIconIfChanged(button: MaterialButton, @DrawableRes icon: Int) {
        if (icons[button.id] == icon) return
        icons[button.id] = icon
        button.setIconResource(icon)
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

    /** Which buttons are drawn highlighted now, so a render repaints only what changed. */
    private val painted = HashMap<Int, Boolean>()

    /**
     * The light, brightness, focus and colour buttons: accent colour when they're not in their
     * default state (or, for brightness and focus, while the ring adjusts them).
     */
    private fun paintStateButton(button: MaterialButton, highlighted: Boolean) {
        if (painted[button.id] == highlighted) return
        painted[button.id] = highlighted
        val context = button.context
        val background = ContextCompat.getColor(context, if (highlighted) R.color.accent else R.color.control_background)
        val foreground = ContextCompat.getColor(context, if (highlighted) R.color.on_accent else R.color.control_foreground)
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.setTextColor(foreground)
        button.iconTint = ColorStateList.valueOf(foreground)
    }

    // ---- Colours -------------------------------------------------------------------------------

    @VisibleForTesting
    internal fun setColourMode(mode: ColorMode, announce: Boolean) {
        val flips = mode.invertsLight != colourMode.invertsLight
        colourMode = mode
        settings.colourMode = mode
        applyPreviewFilter()
        applyFrozenFilter()
        // The brightness keeps its place (the screen's brightness); live, the camera follows it.
        if (flips) applyLiveExposure()
        renderColour()
        // TalkBack hears the button's new state; sighted users see the name for a moment.
        if (announce) showMessage(getString(mode.label), forTalkBack = false)
    }

    /**
     * The live picture, through a hardware layer on the preview's TextureView. The controls are
     * other views, never filtered.
     */
    private fun applyPreviewFilter() {
        val view = binding.previewView
        if (colourMode == ColorMode.NORMAL) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(colourMode.matrix()) })
        }
    }

    /** The colour mode with the frozen brightness: for the frozen picture and what is saved or shared. */
    @VisibleForTesting
    internal fun pictureMatrix(): FloatArray = colourMode.matrix(Brightness.gain(colourMode.lightFor(frozenLevel)))

    private fun applyFrozenFilter() {
        val plain = colourMode == ColorMode.NORMAL && frozenLevel == 0
        binding.frozenView.setPictureFilter(if (plain) null else ColorMatrixColorFilter(ColorMatrix(pictureMatrix())))
    }

    private fun renderColour() = with(binding.colourButton) {
        setText(colourMode.shortLabel)
        ViewCompat.setStateDescription(this, getString(colourMode.label))
        ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, getString(R.string.colour_action), null)
        paintStateButton(this, highlighted = colourMode != ColorMode.NORMAL)
    }

    // ---- Brightness ----------------------------------------------------------------------------

    /** The ring turned brightness to [value]: frozen, the picture's level; live, the camera's exposure. */
    private fun setBrightness(value: Float) {
        if (frozen) {
            frozenLevel = value.roundToInt().coerceIn(-Brightness.FROZEN_LEVELS, Brightness.FROZEN_LEVELS)
            applyFrozenFilter()
        } else {
            brightnessValue = value
            applyLiveExposure(value)
        }
    }

    /** One step darker (−) or lighter (+), like the old darker / lighter buttons. */
    private fun stepBrightness(steps: Int) {
        if (frozen) {
            val next = (frozenLevel + steps).coerceIn(-Brightness.FROZEN_LEVELS, Brightness.FROZEN_LEVELS)
            if (next != frozenLevel) setBrightness(next.toFloat())
        } else {
            val range = exposure ?: return
            val next = range.stepFrom(brightnessValue.roundToInt(), steps).toFloat()
            if (next != brightnessValue) setBrightness(next)
        }
        renderRing()
    }

    /** The brightness level now, in steps from "as the camera sees it" (+ lighter). */
    private fun brightnessLevel(): Int = brightnessLevelAt(if (frozen) frozenLevel.toFloat() else brightnessValue)

    private fun brightnessLevelAt(value: Float): Int = if (frozen) value.roundToInt() else exposure?.level(value.roundToInt()) ?: 0

    /** "Normal", "3 steps lighter", "2 steps darker". */
    private fun spokenBrightness(level: Int): String = when {
        level > 0 -> resources.getQuantityString(R.plurals.brightness_lighter, level, level)
        level < 0 -> resources.getQuantityString(R.plurals.brightness_darker, -level, -level)
        else -> getString(R.string.brightness_normal)
    }

    /**
     * The camera's exposure for the live brightness: + is more light, except in the inverting
     * colour modes, where less light makes the screen lighter.
     */
    private fun applyLiveExposure(sliderValue: Float = brightnessValue) {
        val range = exposure ?: return
        if (frozen) return
        camera?.cameraControl?.setExposureCompensationIndex(range.cameraIndex(sliderValue, colourMode))?.logFailure("exposure")
    }

    /** The camera's exposure-compensation range, for live brightness. */
    private fun readExposure() {
        val state = camera?.cameraInfo?.exposureState
        exposure = if (state != null && state.isExposureCompensationSupported) {
            Brightness.Exposure.of(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        } else {
            null
        }
    }

    /** The live brightness from the camera's exposure: when it opens, and back from frozen. */
    private fun syncBrightnessFromCamera() {
        val range = exposure ?: return
        val index = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: return
        brightnessValue = range.sliderValue(index, colourMode)
    }

    // ---- Freeze --------------------------------------------------------------------------------

    /** Freezes the picture: the screen's picture at once, then the camera's sharp photo in its place. */
    private fun freeze() {
        if (!cameraInFront()) return
        val id = ++freezeGeneration
        frozen = true
        freezeStartMs = SystemClock.elapsedRealtime()
        cameraZoomAtFreeze = currentZoom()?.zoomRatio ?: 1f
        frozenLevel = 0
        lastExport = null
        frozenPicture = null
        // The photo first, so it is of the same moment; then the screen's picture to show meanwhile.
        imageCapture?.let { takePhoto(it, id, cameraZoomAtFreeze) }
        val shot = try {
            binding.previewView.bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "no memory for the screen's picture", e)
            null
        }
        val shownMs = SystemClock.elapsedRealtime() - freezeStartMs
        if (shot != null) {
            placeholder = shot
            showWholeBitmap(shot, lowestScale = FrozenZoom.LOW)
            // With no photo coming, the screen's picture is the frozen picture.
            if (imageCapture == null) frozenPicture = Picture(shot, 0)
        } else if (imageCapture == null) {
            frozen = false
            showMessage(getString(R.string.photo_failed), forTalkBack = true)
            renderFreeze()
            return
        }
        Log.i(TAG, "freeze $id: screen picture ${shot?.width}x${shot?.height} shown after $shownMs ms")
        applyFrozenFilter()
        renderCameraArea()
        renderFreeze()
    }

    /** Shows [bitmap] filling the frozen view, as the screen showed it. */
    private fun showWholeBitmap(bitmap: Bitmap, lowestScale: Float) {
        val view = binding.frozenView
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val transform = PhotoDecode.baseTransform(w, h, 0, Box(0f, 0f, w, h), view.width.toFloat(), view.height.toFloat())
        view.show(bitmap, transform, lowestScale, keepView = false)
    }

    private fun takePhoto(capture: ImageCapture, id: Int, zoomRatio: Float) {
        capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val capturedMs = SystemClock.elapsedRealtime()
                val decoded = try {
                    decodePhoto(image, zoomRatio)
                } catch (e: Throwable) {
                    // Out of memory included: Freeze keeps the screen's picture instead.
                    Log.w(TAG, "the photo couldn't be decoded", e)
                    null
                }
                val decodedMs = SystemClock.elapsedRealtime()
                runOnUiThread {
                    if (decoded == null) onPhotoFailed(id) else onPhoto(id, decoded.first, decoded.second, capturedMs, decodedMs)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "the photo failed (${exception.imageCaptureError})", exception)
                runOnUiThread { onPhotoFailed(id) }
            }
        })
    }

    /**
     * The photo's pixels (fewer at high zoom, see [PhotoDecode.sampleSize]), its rotation, and
     * where the screen was in it. Closes [image] as soon as the bytes are copied.
     */
    private fun decodePhoto(image: ImageProxy, zoomRatio: Float): Pair<Picture, Box> {
        val bytes: ByteArray
        val imageW: Int
        val imageH: Int
        val crop: Rect
        val cameraXRotation: Int
        try {
            val buffer = image.planes[0].buffer
            bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            imageW = image.width
            imageH = image.height
            crop = Rect(image.cropRect)
            cameraXRotation = image.imageInfo.rotationDegrees
        } finally {
            image.close()
        }
        val exif = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } catch (e: IOException) {
            debugLog("no EXIF in the photo", e)
            ExifInterface.ORIENTATION_UNDEFINED
        }
        val rotation = PhotoDecode.uprightRotation(PhotoDecode.exifDegrees(exif), cameraXRotation)
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoDecode.sampleSize(zoomRatio)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("not a JPEG")
        // The crop is in the image's pixels; the bitmap may be smaller (sampled). If the camera
        // turned the pixels itself and the shapes don't match, the whole photo stands in.
        val sameShape = (bitmap.width >= bitmap.height) == (imageW >= imageH)
        val k = bitmap.width / imageW.toFloat()
        val box = if (sameShape && !crop.isEmpty) {
            Box(crop.left * k, crop.top * k, crop.right * k, crop.bottom * k)
        } else {
            Box(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        Log.i(
            TAG,
            "photo ${imageW}x$imageH (${bytes.size / 1024} KB JPEG), crop $crop, rotation CameraX $cameraXRotation / EXIF $exif" +
                " -> $rotation; decoded ${bitmap.width}x${bitmap.height} (sample ${options.inSampleSize})",
        )
        return Picture(bitmap, rotation) to box
    }

    private fun onPhoto(id: Int, picture: Picture, crop: Box, capturedMs: Long, decodedMs: Long) {
        if (id != freezeGeneration || !frozen) {
            picture.bitmap.recycle()
            return
        }
        val view = binding.frozenView
        val bitmap = picture.bitmap
        val transform = PhotoDecode.baseTransform(
            bitmap.width.toFloat(), bitmap.height.toFloat(), picture.rotation, crop, view.width.toFloat(), view.height.toFloat(),
        )
        val old = placeholder
        view.show(bitmap, transform, FrozenZoom.LOW, keepView = old != null)
        placeholder = null
        frozenPicture = picture
        old?.let { recycleLater(it) }
        val now = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "freeze $id: photo taken ${capturedMs - freezeStartMs} ms, decoded ${decodedMs - freezeStartMs} ms," +
                " shown ${now - freezeStartMs} ms after the tap",
        )
        renderFreeze()
    }

    private fun onPhotoFailed(id: Int) {
        if (id != freezeGeneration || !frozen) return
        val shot = placeholder
        if (shot == null) {
            unfreeze()
        } else {
            frozenPicture = Picture(shot, 0)
        }
        showMessage(getString(R.string.photo_failed), forTalkBack = true)
        renderFreeze()
    }

    /** Back to the live picture. The frozen bitmaps are freed once nothing draws or saves them. */
    private fun unfreeze() {
        if (!frozen) return
        frozen = false
        freezeGeneration++
        val old = listOfNotNull(placeholder, frozenPicture?.bitmap).distinct()
        placeholder = null
        frozenPicture = null
        lastExport = null
        pendingSave = false
        binding.frozenView.clear()
        old.forEach { recycleLater(it) }
        syncBrightnessFromCamera()
        renderCameraArea()
        renderFreeze()
    }

    private fun recycleLater(bitmap: Bitmap) {
        if (exporting) {
            recycleAfterExport += bitmap
            return
        }
        // After the next frame, which no longer draws it.
        binding.root.postOnAnimation { binding.root.post { if (bitmap !== binding.frozenView.bitmap) bitmap.recycle() } }
    }

    /** The parts that change between live and frozen. */
    private fun renderFreeze() = with(binding) {
        val ready = frozenPicture != null
        frozenView.isVisible = frozen
        // Busy: the sharp photo is on its way, or a picture is being prepared to save or share.
        photoProgress.isVisible = frozen && (!ready || exporting)
        backToLive.isEnabled = frozen
        previewView.importantForAccessibility =
            if (frozen) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        lightButton.isVisible = !frozen
        focusButton.isVisible = !frozen
        saveButton.isVisible = frozen
        shareButton.isVisible = frozen
        for (button in listOf(saveButton, shareButton)) {
            button.isEnabled = frozen && ready && !exporting
            button.alpha = if (button.isEnabled) 1f else DISABLED_ALPHA
        }
        renderFreezeButton()
        renderRing()
    }

    private fun renderFreezeButton() = with(binding.freezeButton) {
        setText(if (frozen) R.string.live_button else R.string.freeze_button)
        setIconResource(if (frozen) R.drawable.ic_live else R.drawable.ic_freeze)
        isEnabled = frozen || cameraInFront()
        alpha = if (isEnabled) 1f else DISABLED_ALPHA
        ViewCompat.setStateDescription(this, if (frozen) getString(R.string.frozen_state) else null)
        ViewCompat.replaceAccessibilityAction(
            this, AccessibilityActionCompat.ACTION_CLICK,
            getString(if (frozen) R.string.live_action else R.string.freeze_action), null,
        )
    }

    // ---- Save and share ------------------------------------------------------------------------

    /** Save: on Android 9 and older it asks for the storage permission first (the only time it is asked). */
    @VisibleForTesting
    internal fun onSaveButton() {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (PictureStore.needsWritePermission() &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = true
            requestStorage.launch(permission)
            return
        }
        startExport(ExportKind.SAVE)
    }

    private fun onStoragePermission(granted: Boolean) {
        val wanted = pendingSave
        pendingSave = false
        if (granted) {
            if (wanted) startExport(ExportKind.SAVE)
            return
        }
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        showMessage(getString(if (canAskAgain) R.string.save_permission_denied else R.string.save_permission_blocked), forTalkBack = true)
    }

    /**
     * Saves or shares the whole frozen picture (not only the part zoomed into), with the colour
     * mode and brightness the user sees.
     */
    private fun startExport(kind: ExportKind) {
        val picture = frozenPicture ?: return
        if (!frozen || exporting) return
        val key = ExportKey(freezeGeneration, colourMode, frozenLevel)
        val reuse = lastExport?.takeIf { it.first == key }?.second
        val matrix = pictureMatrix()
        val now = System.currentTimeMillis()
        val shareDir = PictureStore.shareDir(this)
        exporting = true
        renderFreeze()
        worker.execute {
            val result = runCatching {
                val file = reuse?.takeIf { it.exists() } ?: renderJpeg(picture, matrix, File(shareDir, PictureStore.fileName(now)))
                if (kind == ExportKind.SAVE) {
                    val uri = PictureStore.save(contentResolver, file, file.name, now)
                    Log.i(TAG, "saved $uri (${file.length() / 1024} KB)")
                }
                file
            }
            runOnUiThread { onExportDone(kind, key, result) }
        }
    }

    /** The picture stood up, through [matrix], as a JPEG. Runs on the worker thread. */
    private fun renderJpeg(picture: Picture, matrix: FloatArray, file: File): File {
        val source = picture.bitmap
        val (w, h) = PhotoDecode.uprightSize(source.width, source.height, picture.rotation)
        val out = createBitmap(w, h)
        try {
            val turn = Matrix().apply {
                setValues(PhotoDecode.rotation(source.width.toFloat(), source.height.toFloat(), picture.rotation).values())
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
            Canvas(out).drawBitmap(source, turn, paint)
            file.parentFile?.mkdirs()
            file.outputStream().buffered().use {
                if (!out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw IOException("JPEG failed")
            }
        } finally {
            out.recycle()
        }
        Log.i(TAG, "picture ${w}x$h written to ${file.name} (${file.length() / 1024} KB)")
        return file
    }

    private fun onExportDone(kind: ExportKind, key: ExportKey, result: Result<File>) {
        exporting = false
        recycleAfterExport.forEach { recycleLater(it) }
        recycleAfterExport.clear()
        renderFreeze()
        val file = result.getOrElse { e ->
            Log.w(TAG, "${kind.name.lowercase()} failed", e)
            showMessage(getString(if (kind == ExportKind.SAVE) R.string.save_failed else R.string.share_failed), forTalkBack = true)
            return
        }
        if (key.freeze == freezeGeneration) lastExport = key to file
        when (kind) {
            ExportKind.SAVE -> showMessage(getString(R.string.saved_message), forTalkBack = true)
            ExportKind.SHARE -> openShareSheet(file)
        }
    }

    private fun openShareSheet(file: File) {
        val uri = try {
            PictureStore.shareUri(this, file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "can't share $file", e)
            showMessage(getString(R.string.share_failed), forTalkBack = true)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PictureStore.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no share sheet", e)
            showMessage(getString(R.string.share_failed), forTalkBack = true)
        }
    }

    // ---- Messages ------------------------------------------------------------------------------

    /**
     * A short message at the top, for a few seconds. [forTalkBack]: TalkBack reads it too (not
     * for the colour mode, which TalkBack already hears as the button's new state).
     */
    private fun showMessage(text: String, forTalkBack: Boolean) = with(binding.messageView) {
        removeCallbacks(hideMessage)
        animate().cancel()
        accessibilityLiveRegion = if (forTalkBack) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        importantForAccessibility = if (forTalkBack) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        this.text = text
        alpha = 1f
        isVisible = true
        postDelayed(hideMessage, if (forTalkBack) LONG_MESSAGE_MS else SHORT_MESSAGE_MS)
    }

    @VisibleForTesting
    internal fun messageShown(): String? = binding.messageView.takeIf { it.isVisible }?.text?.toString()

    /** Unit tests: freezes on [bitmap] as if it were the camera's photo (Robolectric has no camera). */
    @VisibleForTesting
    internal fun freezeForTest(bitmap: Bitmap) {
        freezeGeneration++
        frozen = true
        frozenLevel = 0
        cameraZoomAtFreeze = 1f
        showWholeBitmap(bitmap, lowestScale = FrozenZoom.LOW)
        frozenPicture = Picture(bitmap, 0)
        applyFrozenFilter()
        renderCameraArea()
        renderFreeze()
    }

    @VisibleForTesting
    internal fun unfreezeForTest() = unfreeze()

    // ---- Preview gestures: pinch to zoom, tap to focus -----------------------------------------

    private fun setUpPreviewGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoom = currentZoom() ?: return true
                if (!ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio)) return true
                val ratio = ZoomMath.pinchRatio(zoom.zoomRatio, detector.scaleFactor, zoom.minZoomRatio, zoom.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(ratio)
                rememberZoom(ZoomMath.ratioToLinear(ratio, zoom.minZoomRatio, zoom.maxZoomRatio))
                if (ringMode != RingMode.ZOOM) setRingMode(RingMode.ZOOM)
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

    // ---- Focus: Auto, Locked (tap), Manual (the ring) -------------------------------------------

    @VisibleForTesting
    internal fun onFocusEvent(event: FocusEvent, at: PointF? = null) {
        val old = focusMode
        val new = old.next(event, manualSupported = manualFocus != null)
        focusMode = new
        val generation = ++focusGeneration
        cancelAfSettle()
        if (event == FocusEvent.LEFT_APP) resetFocusOnOpen = true
        // Leaving Manual before the check has a verdict: no verdict, check again next time.
        if (old == FocusMode.MANUAL && new != FocusMode.MANUAL) {
            focusGuard?.cancelCheck()
            binding.root.removeCallbacks(focusGuardTimeout)
        }
        val cam = camera
        if (cam != null) {
            when (new) {
                FocusMode.AUTO -> backToAuto(cam)
                FocusMode.LOCKED -> {
                    val point = at ?: PointF(binding.previewView.width / 2f, binding.previewView.height / 2f)
                    if (old == FocusMode.MANUAL) {
                        // Manual focus's AF_MODE_OFF would override the tap's autofocus: clear it,
                        // let continuous autofocus bring the lens near the subject, then lock.
                        val tap = if (cameraInFront()) binding.focusRing.show(point.x, point.y) else 0
                        clearManualFocus(cam).addListener({
                            if (generation == focusGeneration) waitForAfThenLock(generation) { lockFocusAt(cam, point, tap) }
                        }, ContextCompat.getMainExecutor(this))
                    } else {
                        lockFocusAt(cam, point)
                    }
                }
                FocusMode.MANUAL -> {
                    if (old == FocusMode.LOCKED) cam.cameraControl.cancelFocusAndMetering().logFailure("cancel focus")
                    setManualFocus(cam, focusValue)
                }
            }
        }
        if (new != old) debugLog("focus $old -> $new ($event)")
        renderRing()
    }

    /** Runs [lock] once continuous autofocus has settled (see [AfSettle]), or after its timeout. */
    private fun waitForAfThenLock(generation: Int, lock: () -> Unit) {
        val settle = AfSettle(SystemClock.elapsedRealtime())
        afSettleLock = { if (generation == focusGeneration) lock() }
        afSettle = settle
        binding.root.removeCallbacks(afSettleTimeout)
        binding.root.postDelayed(afSettleTimeout, AfSettle.TIMEOUT_MS + AF_SETTLE_SLACK_MS)
    }

    private fun onAfSettled(settle: AfSettle) {
        if (settle !== afSettle) return
        afSettle = null
        binding.root.removeCallbacks(afSettleTimeout)
        debugLog("autofocus before the lock: ${settle.outcome}")
        val lock = afSettleLock
        afSettleLock = null
        lock?.invoke()
    }

    private fun cancelAfSettle() {
        afSettle?.cancel()
        afSettle = null
        afSettleLock = null
        if (::binding.isInitialized) binding.root.removeCallbacks(afSettleTimeout)
    }

    /** Continuous autofocus again: no tap lock, no manual distance. */
    private fun backToAuto(cam: Camera) {
        // Both always: the camera ignores what isn't set, and LEFT_APP may come while it closes.
        clearManualFocus(cam)
        cam.cameraControl.cancelFocusAndMetering().logFailure("cancel focus")
    }

    /** Focus and meter at [point] once, then keep the lens there: no auto-cancel. */
    private fun lockFocusAt(cam: Camera, point: PointF, ringTap: Int = 0) {
        if (!cameraInFront()) return
        val meteringPoint = binding.previewView.meteringPointFactory.createPoint(point.x, point.y)
        val action = FocusMeteringAction.Builder(
            meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).disableAutoCancel().build()
        val tap = if (ringTap != 0) ringTap else binding.focusRing.show(point.x, point.y)
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

    /** Autofocus off, and the lens at the focus ring's distance. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setManualFocus(cam: Camera, sliderValue: Float) {
        val range = manualFocus ?: return
        val diopters = range.sliderToDiopters(sliderValue)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            .build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options).logFailure("manual focus")
        val guard = focusGuard ?: return
        val wasIdle = guard.state == ManualFocusGuard.State.IDLE
        guard.onRequest(diopters, SystemClock.elapsedRealtime())
        if (wasIdle && guard.state == ManualFocusGuard.State.CHECKING) {
            binding.root.removeCallbacks(focusGuardTimeout)
            binding.root.postDelayed(focusGuardTimeout, ManualFocusGuard.TIMEOUT_MS + FOCUS_GUARD_SLACK_MS)
        }
    }

    /** The guard's time is up: fails it if no frame echoed the manual focus. */
    private fun checkFocusGuardTimeout() {
        val guard = focusGuard ?: return
        if (guard.onTimeout(SystemClock.elapsedRealtime()) == ManualFocusGuard.State.FAILED) onManualFocusIgnored(guard)
    }

    /** The camera ignores manual focus: no focus ring for this camera, and back to Auto. */
    private fun onManualFocusIgnored(guard: ManualFocusGuard) {
        if (guard !== focusGuard || manualFocus == null) return
        Log.w(TAG, "Manual focus turned off for this camera: ${guard.failure}")
        manualFocus = null
        binding.root.removeCallbacks(focusGuardTimeout)
        onFocusEvent(FocusEvent.MANUAL_IGNORED)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun clearManualFocus(cam: Camera): ListenableFuture<Void?> =
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions().also { it.logFailure("clear manual focus") }

    /**
     * Each frame's result, on a camera thread. Outside Manual, the focus ring follows where
     * autofocus put the lens, so taking it over doesn't jump the focus.
     */
    private fun onFrameResult(result: TotalCaptureResult) {
        val settle = afSettle
        if (settle != null && settle.onFrame(
                result.get(CaptureResult.CONTROL_AF_MODE),
                result.get(CaptureResult.CONTROL_AF_STATE),
                SystemClock.elapsedRealtime(),
            )
        ) {
            binding.root.post { onAfSettled(settle) }
        }
        val guard = focusGuard
        if (guard != null && guard.state == ManualFocusGuard.State.CHECKING) {
            val verdict = guard.onResult(
                result.get(CaptureResult.CONTROL_AF_MODE),
                result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                SystemClock.elapsedRealtime(),
            )
            when (verdict) {
                ManualFocusGuard.State.CONFIRMED -> debugLog("manual focus confirmed by the frames")
                ManualFocusGuard.State.FAILED -> binding.root.post { onManualFocusIgnored(guard) }
                else -> Unit
            }
        }
        // Some cameras (the A20) report -1 when they don't know the distance.
        val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf { it >= 0f } ?: return
        val previous = lensDiopters
        lensDiopters = diopters
        if (manualFocus == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusSync < FOCUS_SYNC_MS || abs(diopters - previous) < FOCUS_SYNC_DIOPTERS) return
        lastFocusSync = now
        binding.lensRing.post { syncFocusRingToLens() }
    }

    private fun syncFocusRingToLens() {
        val range = manualFocus ?: return
        val diopters = lensDiopters
        val turning = ringMode == RingMode.FOCUS && binding.lensRing.isDragging
        if (focusMode == FocusMode.MANUAL || turning || diopters.isNaN()) return
        focusValue = range.dioptersToSlider(diopters)
        if (ringMode == RingMode.FOCUS) renderRing()
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
        // Frozen: the keys zoom the frozen picture, and are taken at the ends too.
        if (frozenKeysZoom(keyCode)) {
            if (ringMode != RingMode.ZOOM) setRingMode(RingMode.ZOOM)
            zoomBySteps(VolumeKeyZoom.stepsFor(keyCode))
            return true
        }
        val zoom = currentZoom()
        val result = VolumeKeyZoom.onKeyDown(
            keyCode = keyCode,
            cameraInFront = cameraInFront(),
            currentLinear = zoom?.linearZoom,
            canZoom = zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio),
        )
        if (result.consumed) {
            // The keys zoom: the ring shows it.
            if (ringMode != RingMode.ZOOM) setRingMode(RingMode.ZOOM)
            result.newLinear?.let {
                camera?.cameraControl?.setLinearZoom(it)
                rememberZoom(it)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (frozenKeysZoom(keyCode)) return true
        val zoom = currentZoom()
        val consumed = VolumeKeyZoom.consumesKeyUp(
            keyCode = keyCode,
            cameraInFront = cameraInFront(),
            currentLinear = zoom?.linearZoom,
            canZoom = zoom != null && ZoomMath.canZoom(zoom.minZoomRatio, zoom.maxZoomRatio),
        )
        return consumed || super.onKeyUp(keyCode, event)
    }

    /** A volume key while the frozen picture is in front (no permission screen over it). */
    private fun frozenKeysZoom(keyCode: Int): Boolean =
        frozen && VolumeKeyZoom.stepsFor(keyCode) != 0 && permissionScreen == PermissionScreen.CAMERA && !isFinishing

    // ---- Layout --------------------------------------------------------------------------------

    /** Edge to edge: the picture fills the screen; the controls and panels keep clear of the bars. */
    private fun applyWindowInsets() = with(binding) {
        controlsPadding = intArrayOf(controls.paddingLeft, controls.paddingTop, controls.paddingRight, controls.paddingBottom)
        panelPadding = intArrayOf(
            permissionPanel.paddingLeft, permissionPanel.paddingTop,
            permissionPanel.paddingRight, permissionPanel.paddingBottom,
        )
        topMargins = intArrayOf(
            (messageView.layoutParams as FrameLayout.LayoutParams).topMargin,
            (photoProgress.layoutParams as FrameLayout.LayoutParams).topMargin,
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
            messageView.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = topMargins[0] + bars.top }
            photoProgress.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = topMargins[1] + bars.top }
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

        /** The focus ring follows the lens at most this often, and only for a change this big. */
        private const val FOCUS_SYNC_MS = 250L
        private const val FOCUS_SYNC_DIOPTERS = 0.05f

        /** The guard's timeout runs a little after its deadline, so the last frames count. */
        private const val FOCUS_GUARD_SLACK_MS = 100L
        private const val AF_SETTLE_SLACK_MS = 50L

        /** The biggest photo Freeze asks for: 4:3, about 16 megapixels. */
        private val PHOTO_MAX_SIZE = Size(4640, 3480)
        private const val JPEG_QUALITY = 92
        private const val ZOOM_SAVE_DELAY_MS = 500L

        private const val SHORT_MESSAGE_MS = 1500L
        private const val LONG_MESSAGE_MS = 3500L
        private const val MESSAGE_FADE_MS = 300L

        /** Unit tests turn this off: Robolectric has no camera for CameraX to open. */
        @VisibleForTesting
        internal var cameraStartEnabled = true
        private const val DISABLED_ALPHA = 0.5f
    }
}
