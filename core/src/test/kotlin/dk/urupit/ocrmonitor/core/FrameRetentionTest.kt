package dk.urupit.ocrmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRetentionTest {
    private fun frame(id: Int, at: Long, bytes: Long = 1_000) =
        StoredFrame(name = "frame-$id.jpg", capturedAt = at, sizeBytes = bytes)

    @Test
    fun `nothing to delete when under every budget`() {
        val frames = (1..5).map { frame(it, at = it * 1_000L) }
        val deleted = FrameRetention.selectForDeletion(frames, RetentionPolicy(), now = 10_000)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `deletes oldest beyond the frame count`() {
        val frames = (1..10).map { frame(it, at = it * 1_000L) }
        val deleted = FrameRetention.selectForDeletion(
            frames, RetentionPolicy(maxFrames = 4), now = 20_000,
        )
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L), deleted.map { it.capturedAt })
    }

    @Test
    fun `deletes oldest beyond the byte budget`() {
        val frames = (1..5).map { frame(it, at = it * 1_000L, bytes = 100) }
        val deleted = FrameRetention.selectForDeletion(
            frames, RetentionPolicy(maxTotalBytes = 250), now = 10_000,
        )
        // 250 bytes holds two 100-byte frames; the three oldest go.
        assertEquals(3, deleted.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), deleted.map { it.capturedAt })
    }

    @Test
    fun `newest frame survives even when it alone exceeds the byte budget`() {
        // Otherwise a single oversized capture would delete itself and the dock
        // would silently store nothing at all.
        val frames = listOf(frame(1, at = 1_000, bytes = 10), frame(2, at = 2_000, bytes = 10_000))
        val deleted = FrameRetention.selectForDeletion(
            frames, RetentionPolicy(maxTotalBytes = 100), now = 5_000,
        )
        assertEquals(listOf(1_000L), deleted.map { it.capturedAt })
    }

    @Test
    fun `deletes frames past the age limit`() {
        val frames = listOf(frame(1, at = 1_000), frame(2, at = 50_000), frame(3, at = 90_000))
        val deleted = FrameRetention.selectForDeletion(
            frames, RetentionPolicy(maxAgeMillis = 50_000), now = 100_000,
        )
        assertEquals(listOf(1_000L), deleted.map { it.capturedAt })
    }

    @Test
    fun `returns deletions oldest first`() {
        val frames = (1..20).map { frame(it, at = it * 1_000L) }
        val deleted = FrameRetention.selectForDeletion(
            frames, RetentionPolicy(maxFrames = 3), now = 50_000,
        )
        assertEquals(deleted.map { it.capturedAt }.sorted(), deleted.map { it.capturedAt })
    }

    @Test
    fun `empty input is handled`() {
        assertTrue(FrameRetention.selectForDeletion(emptyList(), RetentionPolicy(), now = 1).isEmpty())
    }
}
