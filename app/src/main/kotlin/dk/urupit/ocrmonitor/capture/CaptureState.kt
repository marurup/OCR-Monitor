package dk.urupit.ocrmonitor.capture

import dk.urupit.ocrmonitor.core.ServiceHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The service's live state, shared with the UI.
 *
 * A process-wide singleton rather than a bound-service connection: the UI is a
 * diagnostic window onto a service whose real job is to keep running with the
 * screen off and nothing bound to it.
 */
object CaptureState {
    private val _health = MutableStateFlow(ServiceHealth.idle())
    val health: StateFlow<ServiceHealth> = _health.asStateFlow()

    fun onServiceStarted() = _health.update {
        it.copy(running = true, lastFailureReason = null)
    }

    fun onServiceStopped() = _health.update { it.copy(running = false) }

    fun onCaptureSucceeded(at: Long, latenessMillis: Long, frames: Int, bytes: Long) =
        _health.update {
            it.copy(
                captureCount = it.captureCount + 1,
                lastCaptureAt = at,
                latenessMillis = latenessMillis,
                storedFrames = frames,
                storedBytes = bytes,
            )
        }

    fun onCaptureFailed(at: Long, reason: String) = _health.update {
        it.copy(
            failureCount = it.failureCount + 1,
            lastFailureAt = at,
            lastFailureReason = reason,
        )
    }

    fun reset() {
        _health.value = ServiceHealth.idle()
    }
}
