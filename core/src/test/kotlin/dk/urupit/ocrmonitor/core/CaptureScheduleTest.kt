package dk.urupit.ocrmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScheduleTest {
    private val schedule = CaptureSchedule(intervalMillis = 60_000)

    @Test
    fun `first capture is due immediately`() {
        assertEquals(0L, schedule.delayUntilNext(lastCaptureAt = null, now = 1_000))
        assertTrue(schedule.isDue(lastCaptureAt = null, now = 1_000))
    }

    @Test
    fun `waits out the remainder of the interval`() {
        assertEquals(40_000L, schedule.delayUntilNext(lastCaptureAt = 0, now = 20_000))
        assertFalse(schedule.isDue(lastCaptureAt = 0, now = 20_000))
    }

    @Test
    fun `due exactly on the interval boundary`() {
        assertEquals(0L, schedule.delayUntilNext(lastCaptureAt = 0, now = 60_000))
    }

    @Test
    fun `overdue capture fires now rather than going negative`() {
        assertEquals(0L, schedule.delayUntilNext(lastCaptureAt = 0, now = 500_000))
    }

    @Test
    fun `clock jumping backwards does not park the loop`() {
        // An NTP correction or a manual clock change can put the recorded last
        // capture in the future. Without this guard the service would sleep
        // until real time caught up, which could be hours.
        assertEquals(0L, schedule.delayUntilNext(lastCaptureAt = 900_000, now = 10_000))
    }

    @Test
    fun `lateness is zero while on schedule`() {
        assertEquals(0L, schedule.latenessMillis(lastCaptureAt = 0, now = 30_000))
        assertEquals(0L, schedule.latenessMillis(lastCaptureAt = 0, now = 60_000))
    }

    @Test
    fun `lateness measures overshoot past the due time`() {
        assertEquals(15_000L, schedule.latenessMillis(lastCaptureAt = 0, now = 75_000))
    }

    @Test
    fun `catch-up tolerance defaults to one interval`() {
        assertFalse(schedule.isBeyondCatchUp(lastCaptureAt = 0, now = 110_000))
        assertTrue(schedule.isBeyondCatchUp(lastCaptureAt = 0, now = 200_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive interval`() {
        CaptureSchedule(intervalMillis = 0)
    }
}
