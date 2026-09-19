package dk.urupit.ocrmonitor.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.urupit.ocrmonitor.Prefs
import dk.urupit.ocrmonitor.capture.CameraCapabilityProbe
import dk.urupit.ocrmonitor.capture.CaptureService
import dk.urupit.ocrmonitor.capture.CaptureState
import dk.urupit.ocrmonitor.capture.FrameStore
import dk.urupit.ocrmonitor.core.CameraAssessment
import dk.urupit.ocrmonitor.core.CameraCapabilities
import dk.urupit.ocrmonitor.core.CaptureSchedule
import dk.urupit.ocrmonitor.core.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SpineScreen(
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
) {
    val context = LocalContext.current
    val health by CaptureState.health.collectAsStateWithLifecycle()
    val prefs = remember { Prefs(context) }
    val frameStore = remember { FrameStore(context) }

    var cameras by remember { mutableStateOf<List<CameraCapabilities>>(emptyList()) }
    var interval by remember { mutableStateOf(prefs.captureIntervalMillis) }

    LaunchedEffect(Unit) {
        cameras = withContext(Dispatchers.IO) { CameraCapabilityProbe.probeAll(context) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("OCR-Monitor", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Camera spine. Proves the phone can capture on a timer with the " +
                    "screen off, and reports what its camera will let us control.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!hasCameraPermission) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera permission needed", fontWeight = FontWeight.Bold)
                        Button(onClick = onRequestCameraPermission) { Text("Grant") }
                    }
                }
            }

            CaptureCard(
                running = health.running,
                intervalMillis = interval,
                onIntervalChange = {
                    interval = it
                    prefs.captureIntervalMillis = it
                    // Restart so the loop picks the new interval up immediately.
                    if (health.running) {
                        CaptureService.stop(context)
                        CaptureService.start(context)
                    }
                },
                onStart = { CaptureService.start(context) },
                onStop = { CaptureService.stop(context) },
                enabled = hasCameraPermission,
            )

            HealthCard(
                captures = health.captureCount,
                failures = health.failureCount,
                lastCaptureAt = health.lastCaptureAt,
                lastFailureReason = health.lastFailureReason,
                latenessMillis = health.latenessMillis,
                storedFrames = health.storedFrames,
                storedBytes = health.storedBytes,
            )

            LatestFrameCard(frameStore, health.lastCaptureAt)

            cameras.forEach { CameraCard(it) }

            ScreenOffTestCard()
        }
    }
}

@Composable
private fun CaptureCard(
    running: Boolean,
    intervalMillis: Long,
    onIntervalChange: (Long) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Capture", style = MaterialTheme.typography.titleMedium)
            Text(if (running) "Running" else "Stopped", fontWeight = FontWeight.Bold)

            Text("Interval: ${formatInterval(intervalMillis)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureSchedule.PRESET_INTERVALS_MILLIS.forEach { preset ->
                    Button(
                        onClick = { onIntervalChange(preset) },
                        enabled = preset != intervalMillis,
                    ) { Text(formatIntervalShort(preset)) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = enabled && !running) { Text("Start") }
                Button(onClick = onStop, enabled = running) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun HealthCard(
    captures: Long,
    failures: Long,
    lastCaptureAt: Long?,
    lastFailureReason: String?,
    latenessMillis: Long,
    storedFrames: Int,
    storedBytes: Long,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Health", style = MaterialTheme.typography.titleMedium)
            Row2("Captures", captures.toString())
            Row2("Failures", failures.toString())
            Row2(
                "Last capture",
                lastCaptureAt?.let {
                    DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), 0).toString()
                } ?: "never",
            )
            if (latenessMillis > 0) Row2("Running late by", formatInterval(latenessMillis))
            Row2("Stored frames", "$storedFrames (${storedBytes / 1024 / 1024} MB)")
            lastFailureReason?.let {
                Text(
                    "Last error: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LatestFrameCard(frameStore: FrameStore, lastCaptureAt: Long?) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(lastCaptureAt) {
        thumbnail = withContext(Dispatchers.IO) {
            frameStore.latest()?.let { file ->
                // Subsample hard: this is a sanity check that the camera is
                // pointed at the right thing, not an inspection tool.
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Latest frame", style = MaterialTheme.typography.titleMedium)
            val bitmap = thumbnail
            if (bitmap == null) {
                Text("No frames captured yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Most recent captured frame",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                "Frames are written to Android/data/${context.packageName}/files/frames " +
                    "so they can be pulled over USB without adb.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CameraCard(capabilities: CameraCapabilities) {
    val assessment = remember(capabilities) { capabilities.assess() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Camera ${capabilities.cameraId} (${capabilities.lensFacing})",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                verdictText(assessment.verdict),
                color = verdictColour(assessment.verdict),
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(4.dp))
            Row2("Hardware level", capabilities.hardwareLevel.name)
            Row2("Torch", capabilities.hasFlashUnit.yesNo())
            Row2("AE lock", capabilities.supportsAutoExposureLock.yesNo())
            Row2("AWB lock", capabilities.supportsAutoWhiteBalanceLock.yesNo())
            Row2("Manual sensor", capabilities.supportsManualSensor.yesNo())
            Row2(
                "Closest focus",
                capabilities.minimumFocusDistanceMetres
                    ?.let { "%.2f m".format(it) } ?: "fixed focus",
            )
            Row2("AF modes", capabilities.autoFocusModes.joinToString().ifEmpty { "none" })
            Row2("Largest JPEG", capabilities.largestJpegSize?.toString() ?: "unknown")
            Row2(
                "Exposure comp",
                "${capabilities.exposureCompensationRange} " +
                    "@ ${"%.3f".format(capabilities.exposureCompensationStepEv)} EV",
            )
            Row2("Sensor orientation", "${capabilities.sensorOrientationDegrees}°")

            assessment.findings.forEach { finding ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "${severityLabel(finding.severity)} ${finding.title}",
                    fontWeight = FontWeight.Bold,
                    color = severityColour(finding.severity),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(finding.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScreenOffTestCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Screen-off test", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Start capture at a 15 or 30 second interval.\n" +
                    "2. Press the power button to blank the screen.\n" +
                    "3. Leave it for five minutes.\n" +
                    "4. Wake the phone and check the capture count here.\n\n" +
                    "If the count kept climbing, the dock works and the platform " +
                    "question is settled. If it stalled, the wake lock or the " +
                    "foreground service is being killed and the log will say which.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun Boolean.yesNo(): String = if (this) "yes" else "no"

private fun verdictText(verdict: CameraAssessment.Verdict): String = when (verdict) {
    CameraAssessment.Verdict.SUITABLE -> "Suitable for dock mode"
    CameraAssessment.Verdict.USABLE_WITH_CARE -> "Usable, with caveats"
    CameraAssessment.Verdict.UNSUITABLE -> "Not suitable for dock mode"
}

@Composable
private fun verdictColour(verdict: CameraAssessment.Verdict): Color = when (verdict) {
    CameraAssessment.Verdict.UNSUITABLE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun severityLabel(severity: Severity): String = when (severity) {
    Severity.BLOCKING -> "BLOCKING"
    Severity.WARNING -> "WARNING"
    Severity.INFO -> "INFO"
}

@Composable
private fun severityColour(severity: Severity): Color = when (severity) {
    Severity.BLOCKING -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun formatInterval(millis: Long): String = when {
    millis < 60_000 -> "${millis / 1000} s"
    millis < 3_600_000 -> "${millis / 60_000} min"
    else -> "${millis / 3_600_000} h"
}

private fun formatIntervalShort(millis: Long): String = when {
    millis < 60_000 -> "${millis / 1000}s"
    millis < 3_600_000 -> "${millis / 60_000}m"
    else -> "${millis / 3_600_000}h"
}
