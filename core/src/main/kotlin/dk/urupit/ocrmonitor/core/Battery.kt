package dk.urupit.ocrmonitor.core

/**
 * The dock phone's own battery.
 *
 * A permanently mains-powered phone held at 100% will swell, which is the
 * usual way these docks die. Android offers no charge limiting, and the app
 * cannot switch mains power anyway, so the app reports and Home Assistant
 * acts: the recommendation below is published as a binary sensor and an HA
 * automation follows it to cycle a smart plug. Keeping the policy here rather
 * than in a template means there is one place to reason about it.
 */
data class BatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Double?,
    val voltageMillivolts: Int?,
    val health: BatteryHealth,
    val capturedAt: Long,
)

enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, UNSPECIFIED_FAILURE, UNKNOWN }

/**
 * Hysteresis band for charge cycling. Charging is recommended off above
 * [upperPercent] and on below [lowerPercent]; between the two the current
 * state is held, so the plug does not chatter around a threshold.
 */
data class ChargePolicy(
    val lowerPercent: Int = 40,
    val upperPercent: Int = 80,
    /** Above this the pack is too hot to charge safely; overrides the band. */
    val maxChargeTemperatureCelsius: Double = 40.0,
) {
    init {
        require(lowerPercent in 0..100) { "lowerPercent out of range" }
        require(upperPercent in 0..100) { "upperPercent out of range" }
        require(lowerPercent < upperPercent) {
            "lowerPercent ($lowerPercent) must be below upperPercent ($upperPercent)"
        }
    }
}

enum class ChargeRecommendation { CHARGE, HOLD, STOP }

object BatteryAdvisor {
    fun recommend(snapshot: BatterySnapshot, policy: ChargePolicy): ChargeRecommendation {
        val tooHot = snapshot.temperatureCelsius?.let { it >= policy.maxChargeTemperatureCelsius }
        if (tooHot == true) return ChargeRecommendation.STOP
        if (snapshot.health == BatteryHealth.OVERHEAT) return ChargeRecommendation.STOP

        return when {
            snapshot.levelPercent <= policy.lowerPercent -> ChargeRecommendation.CHARGE
            snapshot.levelPercent >= policy.upperPercent -> ChargeRecommendation.STOP
            // Inside the band: keep doing whatever we are doing, so the plug
            // does not toggle every time the level wobbles by a percent.
            else -> ChargeRecommendation.HOLD
        }
    }
}
