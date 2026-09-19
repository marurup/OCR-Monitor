package dk.urupit.ocrmonitor.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dk.urupit.ocrmonitor.core.BatteryHealth
import dk.urupit.ocrmonitor.core.BatterySnapshot

/**
 * Reads the dock phone's own battery.
 *
 * A permanently plugged-in phone held at 100% will swell, and the app cannot
 * switch mains power itself. So it reports, and Home Assistant acts on the
 * recommendation from [dk.urupit.ocrmonitor.core.BatteryAdvisor] by cycling a
 * smart plug. Nothing here is wired to MQTT yet; that arrives with the
 * publisher in phase 3.
 */
object BatteryMonitor {

    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): BatterySnapshot? {
        // A null receiver returns the sticky broadcast immediately, so no
        // receiver lifecycle to manage for a point-in-time read.
        val intent: Intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null

        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val levelPercent = if (rawLevel >= 0 && scale > 0) {
            (rawLevel * 100 / scale).coerceIn(0, 100)
        } else {
            return null
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        // EXTRA_TEMPERATURE is tenths of a degree Celsius.
        val rawTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = if (rawTemperature == Int.MIN_VALUE) null else rawTemperature / 10.0

        val rawVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val voltage = if (rawVoltage <= 0) null else rawVoltage

        return BatterySnapshot(
            levelPercent = levelPercent,
            isCharging = isCharging,
            temperatureCelsius = temperature,
            voltageMillivolts = voltage,
            health = health(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
            capturedAt = now,
        )
    }

    private fun health(raw: Int): BatteryHealth = when (raw) {
        BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
        BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
        BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UNSPECIFIED_FAILURE
        else -> BatteryHealth.UNKNOWN
    }
}
