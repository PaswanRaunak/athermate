package io.ather.pro.domain.charging

import io.ather.pro.domain.computation.TelemetryComputation
import io.ather.pro.domain.model.ScooterTelemetry
import kotlin.math.roundToLong

/** An approximate charging deadline; never a measured or guaranteed final battery percentage. */
data class ChargeTimeEstimate(
    val readingAtMs: Long,
    val batterySoc: Double,
    val targetPercent: Int,
    val chargerPowerW: Int,
    val capacityWh: Double,
    val minutesFromReading: Double,
    val targetAtMs: Long,
    val stopAtMs: Long,
    val basisCode: Int
) {
    val basisLabel: String get() = when (basisCode) {
        1 -> "Ather's reported charging time"
        2 -> "Recent charging rate"
        else -> "$chargerPowerW W charger estimate"
    }
    fun isValid(): Boolean = readingAtMs > 0 && batterySoc.isFinite() && batterySoc in 0.0..100.0 &&
        minutesFromReading.isFinite() && minutesFromReading in 0.0..2_880.0 &&
        targetAtMs >= readingAtMs && stopAtMs in readingAtMs..targetAtMs &&
        targetPercent in 0..100 && capacityWh.isFinite() && capacityWh > 0 &&
        chargerPowerW in ChargeTimeEstimator.CHARGER_POWERS
}

object ChargeTimeEstimator {
    val CHARGER_POWERS = listOf(350, 700, 900)
    const val MAX_INITIAL_READING_AGE_MS = 30 * 60_000L
    private const val EARLY_STOP_MARGIN_MS = 60_000L

    fun estimate(telemetry: ScooterTelemetry?, target: Int, capacityWh: Double, powerW: Int,
        readingAtMs: Long, nowMs: Long, observedRate: Double? = null,
        charging: Boolean = ChargingControl.isActivelyCharging(telemetry)): ChargeTimeEstimate? {
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        if (powerW !in CHARGER_POWERS || !capacityWh.isFinite() || capacityWh <= 0) return null
        val anchor = if (charging) readingAtMs else nowMs
        if (anchor <= 0 || nowMs - anchor !in 0L..MAX_INITIAL_READING_AGE_MS) return null
        val percent = target.coerceIn(0, 100)
        val values = TelemetryComputation.requireEngine().chargeTimeEstimate(soc, percent.toDouble(),
            capacityWh, powerW.toDouble(),
            if (charging) telemetry.timeToEightyChargeMin ?: Double.NaN else Double.NaN,
            if (charging) telemetry.timeToFullChargeMin ?: Double.NaN else Double.NaN,
            if (charging) observedRate ?: Double.NaN else Double.NaN)
        val minutes = values[0].takeIf { it.isFinite() && it in 0.0..2_880.0 } ?: return null
        val duration = (minutes * 60_000).roundToLong()
        val margin = minOf(EARLY_STOP_MARGIN_MS, duration / 10)
        return ChargeTimeEstimate(anchor, soc, percent, powerW, capacityWh, minutes,
            anchor + duration, anchor + duration - margin, values[1].toInt())
    }
}

/** Uses only new source-timestamped charging measurements; repeated snapshots cannot learn a rate. */
class ChargingRateTracker {
    private var anchorTime: Long? = null
    private var anchorSoc: Double? = null
    private var latestTime: Long? = null
    var percentPerMinute: Double? = null
        private set

    fun reset() { anchorTime = null; anchorSoc = null; latestTime = null; percentPerMinute = null }

    fun observe(telemetry: ScooterTelemetry?, readingAtMs: Long?, nowMs: Long) {
        if (!ChargingControl.isActivelyCharging(telemetry)) { reset(); return }
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return
        val at = readingAtMs ?: return
        if (nowMs - at !in 0L..ChargeTimeEstimator.MAX_INITIAL_READING_AGE_MS || at <= (latestTime ?: 0L)) return
        val start = anchorTime
        val first = anchorSoc
        if (start == null || first == null || at - start > 30 * 60_000L || soc < first) {
            anchorTime = at; anchorSoc = soc; percentPerMinute = null
        } else if (at - start >= 30_000L && soc - first >= 0.1) {
            percentPerMinute = ((soc - first) * 60_000 / (at - start)).takeIf { it in 0.01..10.0 }
        }
        latestTime = at
    }
}
