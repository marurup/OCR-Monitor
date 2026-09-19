package dk.urupit.ocrmonitor.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dk.urupit.ocrmonitor.MainActivity
import dk.urupit.ocrmonitor.Prefs
import dk.urupit.ocrmonitor.R
import dk.urupit.ocrmonitor.core.CaptureSchedule
import dk.urupit.ocrmonitor.core.RetentionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures a frame on a timer, with the screen off, indefinitely.
 *
 * This is the whole reason the project is a native Android app rather than a
 * PWA. A [LifecycleService] is used because CameraX binds to a LifecycleOwner,
 * and the service's own lifecycle is what keeps the camera alive once no
 * activity is in the foreground.
 *
 * On API 29+ the service declares the camera foreground type; on API 24-28
 * none of that machinery exists and a plain foreground service suffices.
 */
class CaptureService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var frameStore: FrameStore

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        frameStore = FrameStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // startForeground must happen before anything else, including a stop:
        // if this was delivered via startForegroundService, failing to call it
        // within the grace window crashes the process on API 26+.
        startInForeground()

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        CaptureState.onServiceStarted()

        if (captureJob?.isActive != true) {
            captureJob = lifecycleScope.launch { runCaptureLoop() }
        }

        // START_STICKY so the dock comes back if the system reclaims the
        // process. It cannot recover across a reboot, though: see the boot
        // note in docs/DESIGN.md.
        return START_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        captureJob = null
        releaseCamera()
        releaseWakeLock()
        CaptureState.onServiceStopped()
        super.onDestroy()
    }

    // ---- capture loop ----

    private suspend fun runCaptureLoop() {
        val schedule = CaptureSchedule(prefs.captureIntervalMillis)
        var lastCaptureAt: Long? = null

        while (true) {
            val now = System.currentTimeMillis()
            val wait = schedule.delayUntilNext(lastCaptureAt, now)
            if (wait > 0) {
                delay(wait)
                continue
            }

            val lateness = schedule.latenessMillis(lastCaptureAt, System.currentTimeMillis())
            val capturedAt = System.currentTimeMillis()

            try {
                captureOneFrame(capturedAt)
                frameStore.prune(RetentionPolicy(), capturedAt)
                CaptureState.onCaptureSucceeded(
                    at = capturedAt,
                    latenessMillis = lateness,
                    frames = frameStore.list().size,
                    bytes = frameStore.totalBytes(),
                )
                updateNotification()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "Capture failed", error)
                CaptureState.onCaptureFailed(capturedAt, error.messageOrClass())
                updateNotification()
            }

            lastCaptureAt = capturedAt
        }
    }

    private suspend fun captureOneFrame(capturedAt: Long) {
        val capture = ensureCameraBound()
        val target = frameStore.newFrameFile(capturedAt)
        val options = ImageCapture.OutputFileOptions.Builder(target).build()

        suspendCancellableCoroutine<Unit> { continuation ->
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        continuation.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // A half-written file would look like a valid frame to
                        // everything downstream.
                        runCatching { target.delete() }
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
    }

    private suspend fun ensureCameraBound(): ImageCapture {
        imageCapture?.let { return it }

        val provider = cameraProvider ?: awaitCameraProvider().also { cameraProvider = it }

        val capture = ImageCapture.Builder()
            // Quality over latency: nothing here is time-critical, and
            // segmentation needs every bit of detail it can get.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val selector = if (prefs.useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, capture)
        imageCapture = capture
        return capture
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                },
                ContextCompat.getMainExecutor(this),
            )
        }

    private fun releaseCamera() {
        runCatching { cameraProvider?.unbindAll() }
        imageCapture = null
        cameraProvider = null
    }

    // ---- foreground plumbing ----

    private fun startInForeground() {
        val notification = buildNotification(CaptureState.health.value.captureCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(CaptureState.health.value.captureCount))
    }

    private fun buildNotification(captureCount: Long): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, captureCount))
            .setSmallIcon(R.drawable.ic_capture)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(channel)
    }

    /**
     * The dock is mains powered, so a partial wake lock held for the service's
     * lifetime is the simple, reliable choice: it keeps the CPU up so the
     * timer fires on schedule with the screen off, without juggling alarms.
     * Revisit if the app ever runs on battery.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun Exception.messageOrClass(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "OcrMonitor::capture"

        const val ACTION_STOP = "dk.urupit.ocrmonitor.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
