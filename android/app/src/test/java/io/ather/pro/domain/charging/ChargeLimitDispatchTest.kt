package io.ather.pro.domain.charging

import io.ather.pro.data.api.AtherApiClient
import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

/** Exercise sparse API frames through the cutoff and the same dispatcher used by Pause. */
class ChargeLimitDispatchTest {
    @Test fun overshootSendsPauseAndRetriesUntilPhysicalStopEvenAfterEcho() {
        var now = 1_000L
        val calls = mutableListOf<Boolean>()
        val dispatcher = RemoteChargingDispatcher(RemoteChargingGateway { _, _, start, callback ->
            calls += start
            callback(Result.success(Unit))
        }, nowMs = { now })
        val parser = AtherApiClient()
        var evidence = ChargingEvidence()
        var telemetry: ScooterTelemetry? = null
        var limit = ChargeLimitController.applySettings(ChargeLimitController.Snapshot(), true, 70)
        var command = RemoteChargingCommand()
        fun receive(json: String, at: Long) {
            now = at
            val delta = parser.parseTelemetry(json)!!
            evidence = evidence.observe(delta, now)
            telemetry = telemetry?.mergeWith(delta) ?: delta
            when (val decision = ChargeLimitController.onTelemetry(limit, telemetry, evidence.batteryAt, now,
                chargingUpdatedMs = evidence.chargingAt)) {
                is ChargeLimitController.Decision.RequestStop -> {
                    limit = decision.next
                    command = dispatcher.attempt(false, "test-token", "test-scooter", telemetry, command) {}.command
                }
                is ChargeLimitController.Decision.StateOnly -> limit = decision.next
                ChargeLimitController.Decision.None -> Unit
            }
        }
        receive("""{"telemetry":{"bike":{"battery_soc":69},"charging":{"chargingStatus":"Charging","chargerConnected":"On"}}}""", 1_000)
        assertTrue(calls.isEmpty())
        // SoC and charge flags do not have to arrive in one packet or the same 30 seconds.
        receive("""{"telemetry":{"bike":{"battery_soc":72}}}""", 61_000)
        assertEquals(listOf(false), calls)
        receive("""{"scooters":{"remote_charging":{"action":"stop"}}}""", 62_000)
        assertEquals(ChargeLimitController.Status.PENDING, limit.status)
        assertTrue(ChargingControl.isActivelyCharging(telemetry))
        receive("""{"telemetry":{"bike":{"battery_soc":73},"charging":{"chargingStatus":"Charging"}}}""", 106_000)
        assertEquals(ChargeLimitController.Status.ERROR, limit.status)
        receive("""{"telemetry":{"bike":{"battery_soc":74}}}""", 121_000)
        assertEquals(listOf(false, false), calls)
        receive("""{"telemetry":{"charging":{"chargingStatus":"Paused"}}}""", 122_000)
        assertEquals(ChargeLimitController.Status.CONFIRMED, limit.status)
        receive("""{"telemetry":{"bike":{"battery_soc":74}}}""", 123_000)
        assertEquals(2, calls.size)
    }
}
