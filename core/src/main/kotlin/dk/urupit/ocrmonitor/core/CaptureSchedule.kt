package dk.urupit.ocrmonitor.core

/**
 * Decides when the next frame is due.
 *
 * Deliberately not a timer: the service asks this what to do next, so the
 * policy stays testable and the same rules apply whether the loop was woken on
 * time, late, or after the process was restarted.
 */
class CaptureSchedule(
    val intervalMillis: Long,
    /**
     * How late a capture may run before the schedule stops trying to catch up.
     * Past this, we simply capture now and realign to the new cadence rather
     * than firing a burst of backdated captures that would all show the same
     * reading anyway.
     */
    private val catchUpToleranceMillis: Long = intervalMillis,
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive, was $intervalMillis" }
    }

    /** Milliseconds to wait before capturing again. Zero means capture now. */
    fun delayUntilNext(lastCaptureAt: Long?, now: Long): Long {
        if (lastCaptureAt == null) return 0L

        // A clock that jumped backwards (NTP correction, manual set) would
        // otherwise park the loop for an arbitrarily long time.
        if (lastCaptureAt > now) return 0L

        val due = lastCaptureAt + intervalMillis
        return (due - now).coerceAtLeast(0L)
    }

    fun isDue(lastCaptureAt: Long?, now: Long): Boolean = delayUntilNext(lastCaptureAt, now) == 0L

    /**
     * How far behind schedule we are. The service publishes this as a
     * diagnostic: a dock that is persistently late is being throttled or
     * starved, and that is worth seeing before readings start disappearing.
     */
    fun latenessMillis(lastCaptureAt: Long?, now: Long): Long {
        if (lastCaptureAt == null || lastCaptureAt > now) return 0L
        val due = lastCaptureAt + intervalMillis
        return (now - due).coerceAtLeast(0L)
    }

    fun isBeyondCatchUp(lastCaptureAt: Long?, now: Long): Boolean =
        latenessMillis(lastCaptureAt, now) > catchUpToleranceMillis

    companion object {
        /**
         * Leak detection needs roughly minute resolution to tell a running tap
         * from normal draw; a battery state of charge does not move fast enough
         * to justify it. Both are profile settings, these are only defaults.
         */
        const val DEFAULT_INTERVAL_MILLIS: Long = 60_000L
        val PRESET_INTERVALS_MILLIS: List<Long> =
            listOf(15_000L, 30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
    }
}
