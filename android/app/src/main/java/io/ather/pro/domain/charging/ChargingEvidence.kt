package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry

/** Sparse GPS/odometer packets must never make old charge readings look fresh. */
data class ChargingEvidence(
    val batteryAt: Long? = null,
    val chargingAt: Long? = null
) {
    val completeAt: Long?
        get() = batteryAt?.let { battery -> chargingAt?.let { minOf(battery, it) } }

    fun observe(delta: ScooterTelemetry, now: Long): ChargingEvidence = copy(
        batteryAt = if (delta.batterySoc?.let { it.isFinite() && it in 0.0..100.0 } == true) now else batteryAt,
        chargingAt = if (hasChargeReading(delta)) now else chargingAt
    )

    companion object {
        fun hasChargeReading(telemetry: ScooterTelemetry): Boolean =
            telemetry.charging != null || telemetry.chargerConnected == false ||
                ChargingControl.isActiveStatus(telemetry.chargingStatus) ||
                ChargingControl.isStoppedStatus(telemetry.chargingStatus)
    }
}
