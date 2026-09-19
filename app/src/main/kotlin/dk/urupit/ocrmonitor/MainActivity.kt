package dk.urupit.ocrmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dk.urupit.ocrmonitor.ui.SpineScreen
import dk.urupit.ocrmonitor.ui.theme.OcrMonitorTheme

/**
 * The on-device UI is deliberately thin: enough to grant permissions, read the
 * camera capability report, and start the capture loop before walking away.
 * Real configuration belongs in the local web UI (phase 4), because a phone in
 * a meter cupboard is a miserable thing to operate by hand.
 */
class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial only costs us the ongoing notification's visibility. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCameraPermission = isCameraGranted()

        setContent {
            OcrMonitorTheme {
                SpineScreen(
                    hasCameraPermission = hasCameraPermission,
                    onRequestCameraPermission = { requestCamera.launch(Manifest.permission.CAMERA) },
                )
            }
        }

        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Permission can change while we are backgrounded, via Settings.
        hasCameraPermission = isCameraGranted()
    }

    private fun isCameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
