package dk.urupit.ocrmonitor.core

/**
 * Which stored frames to delete.
 *
 * The spine keeps every frame so captures can be pulled off the device and the
 * reader tuned against real images. Unbounded, that fills the phone within
 * days at one frame a minute, so retention is enforced on each capture.
 */
data class RetentionPolicy(
    val maxFrames: Int = 500,
    val maxTotalBytes: Long = 256L * 1024 * 1024,
    val maxAgeMillis: Long? = null,
) {
    init {
        require(maxFrames > 0) { "maxFrames must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
    }
}

data class StoredFrame(val name: String, val capturedAt: Long, val sizeBytes: Long)

object FrameRetention {
    /**
     * Returns the frames to delete, oldest first. Newest frames are always kept
     * in preference to older ones, so the most recent capture survives even if
     * it alone exceeds the byte budget.
     */
    fun selectForDeletion(
        frames: List<StoredFrame>,
        policy: RetentionPolicy,
        now: Long,
    ): List<StoredFrame> {
        if (frames.isEmpty()) return emptyList()

        val newestFirst = frames.sortedByDescending { it.capturedAt }
        val doomed = LinkedHashSet<StoredFrame>()

        policy.maxAgeMillis?.let { maxAge ->
            newestFirst.filterTo(doomed) { now - it.capturedAt > maxAge }
        }

        var kept = 0
        var keptBytes = 0L
        for (frame in newestFirst) {
            if (frame in doomed) continue
            val wouldBeCount = kept + 1
            val wouldBeBytes = keptBytes + frame.sizeBytes
            // Always keep at least the newest surviving frame, whatever its size.
            val overBudget = kept > 0 &&
                (wouldBeCount > policy.maxFrames || wouldBeBytes > policy.maxTotalBytes)
            if (overBudget) {
                doomed += frame
            } else {
                kept = wouldBeCount
                keptBytes = wouldBeBytes
            }
        }

        return doomed.sortedBy { it.capturedAt }
    }
}
