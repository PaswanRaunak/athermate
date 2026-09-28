package io.ather.pro.domain.range

import io.ather.pro.domain.model.ScooterTelemetry

data class RideModeRange(val name: String, val km: Double, val active: Boolean)

data class ChargeTargetEstimate(
    val remainingPercent: Double,
    val energyKWh: Double,
    val costInr: Double,
    val rangeAtTargetKm: Double?,
    val minutesToTarget: Double?
)

/** Estimates use reported range and the selected usable battery capacity, never a fabricated SoH. */
object RangeEstimator {
    private fun key(value: String?) = value.orEmpty().filter(Char::isLetterOrDigit).lowercase()
    private fun valid(value: Double?) = value?.takeIf { it.isFinite() && it >= 0.0 }

    fun modes(telemetry: ScooterTelemetry?): List<RideModeRange> = telemetry?.modeRanges.orEmpty()
        .mapNotNull { (name, range) ->
            (valid(range.predictedRangeKm) ?: valid(range.rawRangeKm))?.let {
                RideModeRange(name, it, key(name) == key(telemetry?.mode))
            }
        }.sortedWith(compareBy<RideModeRange> { listOf("smarteco", "eco", "ride", "sport", "warp", "warpplus").indexOf(key(it.name)).let { n -> if (n < 0) 99 else n } }.thenBy { it.name })

    fun current(telemetry: ScooterTelemetry?): Double? = valid(telemetry?.rangeKm)
        ?: modes(telemetry).firstOrNull { it.active }?.km

    fun target(telemetry: ScooterTelemetry?, target: Int, capacityWh: Double, tariff: Double): ChargeTargetEstimate? {
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        if (!capacityWh.isFinite() || capacityWh <= 0 || !tariff.isFinite() || tariff < 0) return null
        val percent = target.coerceIn(0, 100).toDouble()
        val remaining = (percent - soc).coerceAtLeast(0.0)
        val energy = capacityWh * remaining / 100_000.0
        // Interpolate from the nearest reported ETA. Charging taper makes this approximate.
        val eta = when {
            remaining == 0.0 -> 0.0
            percent <= 80 && soc < 80 && valid(telemetry.timeToEightyChargeMin) != null ->
                telemetry.timeToEightyChargeMin!! * remaining / (80.0 - soc)
            soc < 100 && valid(telemetry.timeToFullChargeMin) != null ->
                telemetry.timeToFullChargeMin!! * remaining / (100.0 - soc)
            else -> null
        }
        return ChargeTargetEstimate(remaining, energy, energy * tariff,
            if (soc >= 5) current(telemetry)?.let { it * percent / soc } else null, eta)
    }
}
