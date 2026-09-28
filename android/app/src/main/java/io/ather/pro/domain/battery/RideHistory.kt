package io.ather.pro.domain.battery

import io.ather.pro.domain.model.ScooterTelemetry

data class RideSample(val timestamp: Long, val speedKmh: Double? = null, val odometerKm: Double? = null, val rangeKm: Double? = null)
data class ChartPoint(val timestamp: Long, val value: Double)
enum class RideMetric(val label: String, val unit: String) {
    SPEED("Speed", "km/h"), DISTANCE("Distance travelled", "km"), RANGE("Available range", "km")
}

/** Keep independent measurements nullable: an absent speed reading is not 0 km/h. */
object RideHistory {
    const val MAX_SAMPLES = 7_200
    fun record(history: List<RideSample>, report: ScooterTelemetry, at: Long): List<RideSample> {
        if (at <= 0) return history
        val speed = report.gps?.speed?.takeIf { it.isFinite() && it in 0.0..200.0 }
        val odo = report.odoKm?.takeIf { it.isFinite() && it >= 0 }
        val range = report.rangeKm?.takeIf { it.isFinite() && it >= 0 }
        if (speed == null && odo == null && range == null) return history
        val previous = history.lastOrNull()
        if (previous != null && at < previous.timestamp) return history
        if (previous != null && at - previous.timestamp < 1_000) {
            return history.dropLast(1) + previous.copy(speedKmh = speed ?: previous.speedKmh,
                odometerKm = odo ?: previous.odometerKm, rangeKm = range ?: previous.rangeKm)
        }
        return (history + RideSample(at, speed, odo, range)).takeLast(MAX_SAMPLES)
    }

    fun series(history: List<RideSample>, metric: RideMetric, from: Long, to: Long): List<ChartPoint> {
        val visible = history.filter { it.timestamp in from..to }.sortedBy { it.timestamp }.distinctBy { it.timestamp }
        val baseline = visible.firstNotNullOfOrNull { it.odometerKm }
        return visible.mapNotNull { sample ->
            val value = when (metric) {
                RideMetric.SPEED -> sample.speedKmh
                RideMetric.RANGE -> sample.rangeKm
                RideMetric.DISTANCE -> sample.odometerKm?.let { it - (baseline ?: it) }
            }
            value?.takeIf { it.isFinite() && it >= 0 }?.let { ChartPoint(sample.timestamp, it) }
        }
    }
}
