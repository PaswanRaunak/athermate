package io.ather.pro.domain.charging

/**
 * Outbound Cerberus device-shadow charging mutation.
 * Implementations must POST the verified desired.remote_charging payload.
 */
fun interface RemoteChargingGateway {
    fun setRemoteCharging(
        token: String,
        scooterUuid: String,
        start: Boolean,
        callback: (Result<Unit>) -> Unit
    )
}
