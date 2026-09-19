package dk.urupit.ocrmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHealthTest {
    private fun health(running: Boolean = true, lastCaptureAt: Long? = 0) =
        ServiceHealth.idle().copy(running = running, lastCaptureAt = lastCaptureAt)

    @Test
    fun `not stale while captures are recent`() {
        assertFalse(health(lastCaptureAt = 0).isStale(intervalMillis = 60_000, now = 120_000))
    }

    @Test
    fun `stale after several missed intervals`() {
        assertTrue(health(lastCaptureAt = 0).isStale(intervalMillis = 60_000, now = 300_000))
    }

    @Test
    fun `a stopped service is not stale`() {
        // Deliberately stopped is not a fault, and should not raise an alert.
        assertFalse(health(running = false, lastCaptureAt = 0).isStale(60_000, now = 10_000_000))
    }

    @Test
    fun `never-captured service is not yet stale`() {
        assertFalse(health(lastCaptureAt = null).isStale(60_000, now = 10_000_000))
        assertNull(health(lastCaptureAt = null).millisSinceLastCapture(now = 1_000))
    }

    @Test
    fun `elapsed time never goes negative`() {
        assertEquals(0L, health(lastCaptureAt = 5_000).millisSinceLastCapture(now = 1_000))
    }
}
