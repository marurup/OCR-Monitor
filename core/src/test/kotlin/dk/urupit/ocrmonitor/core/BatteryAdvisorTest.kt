package dk.urupit.ocrmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAdvisorTest {
    private val policy = ChargePolicy(lowerPercent = 40, upperPercent = 80)

    private fun snapshot(
        level: Int,
        charging: Boolean = true,
        temperature: Double? = 25.0,
        health: BatteryHealth = BatteryHealth.GOOD,
    ) = BatterySnapshot(
        levelPercent = level,
        isCharging = charging,
        temperatureCelsius = temperature,
        voltageMillivolts = 4_000,
        health = health,
        capturedAt = 0,
    )

    @Test
    fun `charges below the lower bound`() {
        assertEquals(ChargeRecommendation.CHARGE, BatteryAdvisor.recommend(snapshot(20), policy))
        assertEquals(ChargeRecommendation.CHARGE, BatteryAdvisor.recommend(snapshot(40), policy))
    }

    @Test
    fun `stops above the upper bound`() {
        assertEquals(ChargeRecommendation.STOP, BatteryAdvisor.recommend(snapshot(80), policy))
        assertEquals(ChargeRecommendation.STOP, BatteryAdvisor.recommend(snapshot(95), policy))
    }

    @Test
    fun `holds inside the band so the plug does not chatter`() {
        assertEquals(ChargeRecommendation.HOLD, BatteryAdvisor.recommend(snapshot(60), policy))
    }

    @Test
    fun `temperature overrides a low charge level`() {
        val hot = snapshot(level = 10, temperature = 41.0)
        assertEquals(ChargeRecommendation.STOP, BatteryAdvisor.recommend(hot, policy))
    }

    @Test
    fun `overheat health overrides a low charge level`() {
        val hot = snapshot(level = 10, temperature = null, health = BatteryHealth.OVERHEAT)
        assertEquals(ChargeRecommendation.STOP, BatteryAdvisor.recommend(hot, policy))
    }

    @Test
    fun `missing temperature does not block charging`() {
        val unknownTemp = snapshot(level = 10, temperature = null)
        assertEquals(ChargeRecommendation.CHARGE, BatteryAdvisor.recommend(unknownTemp, policy))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an inverted band`() {
        ChargePolicy(lowerPercent = 80, upperPercent = 40)
    }
}
