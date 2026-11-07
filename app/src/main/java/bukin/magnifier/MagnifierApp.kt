package bukin.magnifier

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/**
 * CameraX set-up: CameraX looks only at the rear camera (the magnifier never uses the others), and
 * starts as early as the app does.
 */
class MagnifierApp : Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        // Start CameraX now, while the screen is still being built, so the camera opens sooner.
        // MainActivity gets the same instance. Only once the user has allowed the camera.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ProcessCameraProvider.getInstance(this)
        }
    }

    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .setMinimumLoggingLevel(Log.WARN)
            .build()
}
