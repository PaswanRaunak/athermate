package io.ather.pro.data.api

import io.ather.pro.domain.charging.RemoteChargingGateway
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.domain.model.VehicleProfile
import okhttp3.WebSocket

/** Cloud transport boundary, including the snapshot obtained by a new connection. */
interface AtherCloudApi {
    interface Listener {
        fun onConnected(socket: WebSocket)
        fun onTelemetry(telemetry: ScooterTelemetry)
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    fun connect(token: String, uuid: String, listener: Listener): WebSocket
    fun subscribe(socket: WebSocket): Boolean
    fun asRemoteChargingGateway(): RemoteChargingGateway
    fun fetchVehicleProfile(token: String, uuid: String, callback: (Result<VehicleProfile>) -> Unit)
    fun fetchRides(
        token: String,
        scooterId: String,
        usableCapacityWh: Double,
        tariffRatePerKWh: Double,
        callback: (Result<List<TripRecord>>) -> Unit
    )
}
