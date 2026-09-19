package dk.urupit.ocrmonitor.core

/**
 * What the dock reports about itself.
 *
 * Published to Home Assistant alongside readings so that "no reading in thirty
 * minutes" is an ordinary alert rather than something noticed weeks later.
 */
data class ServiceHealth(
    val running: Boolean,
    val captureCount: Long,
    val failureCount: Long,
    val lastCaptureAt: Long?,
    val lastFailureAt: Long?,
    val lastFailureReason: String?,
    val latenessMillis: Long,
    val storedFrames: Int,
    val storedBytes: Long,
    val battery: BatterySnapshot?,
) {
    fun millisSinceLastCapture(now: Long): Long? = lastCaptureAt?.let { (now - it).coerceAtLeast(0L) }

    /**
     * A dock is stale once it has missed several consecutive captures. One
     * missed frame is noise; [STALE_INTERVAL_MULTIPLIER] in a row is a fault.
     */
    fun isStale(intervalMillis: Long, now: Long): Boolean {
        if (!running) return false
        val since = millisSinceLastCapture(now) ?: return false
        return since > intervalMillis * STALE_INTERVAL_MULTIPLIER
    }

    companion object {
        const val STALE_INTERVAL_MULTIPLIER = 3

        fun idle(): ServiceHealth = ServiceHealth(
            running = false,
            captureCount = 0,
            failureCount = 0,
            lastCaptureAt = null,
            lastFailureAt = null,
            lastFailureReason = null,
            latenessMillis = 0,
            storedFrames = 0,
            storedBytes = 0,
            battery = null,
        )
    }
}
