package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry

/** Sparse GPS/odometer packets must never make old charge readings look fresh. */
data class ChargingEvidence(
    val batteryAt: Long? = null,
    val chargingAt: Long? = null,
    val batterySourceAt: Long? = null
) {
    val completeAt: Long?
        get() = batteryAt?.let { battery -> chargingAt?.let { minOf(battery, it) } }

    fun observe(delta: ScooterTelemetry, now: Long, snapshot: Boolean = false): ChargingEvidence {
        val source = delta.sourceTimestampMs
        val usable = if (source != null) source > 0 && now - source in 0..MAX_SOURCE_AGE_MS else !snapshot
        val hasBattery = delta.batterySoc?.let { it.isFinite() && it in 0.0..100.0 } == true
        return copy(
            batteryAt = when {
                !hasBattery -> batteryAt
                !usable -> null
                source != null && batterySourceAt != null && source <= batterySourceAt -> batteryAt
                else -> now
            },
            batterySourceAt = if (hasBattery && usable && source != null) maxOf(source, batterySourceAt ?: source) else batterySourceAt,
            // Source time prevents an old paused snapshot from confirming a new stop.
            chargingAt = when {
                !hasChargeReading(delta) -> chargingAt
                !usable -> null
                else -> source ?: now
            }
        )
    }

    companion object {
        const val MAX_SOURCE_AGE_MS = 120_000L
        fun hasChargeReading(telemetry: ScooterTelemetry): Boolean =
            telemetry.charging != null || telemetry.chargerConnected == false ||
                ChargingControl.isActiveStatus(telemetry.chargingStatus) ||
                ChargingControl.isStoppedStatus(telemetry.chargingStatus)
    }
}
