package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class ChargeCutoffReliabilityTest {
    private val active = ScooterTelemetry(batterySoc = 72.0, charging = true, chargerConnected = true)
    @Test fun seventyPercentLimitAlsoStopsAnOvershoot() {
        val state = ChargeLimitController.applySettings(ChargeLimitController.Snapshot(), true, 70)
        assertTrue(ChargeLimitController.onTelemetry(state, active, 1_000, 1_000) is ChargeLimitController.Decision.RequestStop)
    }
    @Test fun stopRetriesAreFreshSpacedAndBounded() {
        val initial = ChargeLimitController.applySettings(ChargeLimitController.Snapshot(), true, 70)
        var state = (ChargeLimitController.onTelemetry(initial, active, 1_000, 1_000) as ChargeLimitController.Decision.RequestStop).next
        for (attempt in 1..3) {
            assertEquals(attempt, state.attempts)
            val sentAt = state.pendingSinceMs!!
            state = (ChargeLimitController.onTelemetry(state, active, sentAt, sentAt + 45_000) as ChargeLimitController.Decision.StateOnly).next
            assertEquals(ChargeLimitController.Status.ERROR, state.status)
            assertEquals(ChargeLimitController.Decision.None, ChargeLimitController.onTelemetry(state, active, sentAt, sentAt + 60_000))
            val next = ChargeLimitController.onTelemetry(state, active, sentAt + 60_000, sentAt + 60_000)
            if (attempt < 3) state = (next as ChargeLimitController.Decision.RequestStop).next
            else assertEquals(ChargeLimitController.Decision.None, next)
        }
    }
    @Test fun aFreshChargingFlagOverridesAnOldPausedStatus() {
        val old = ScooterTelemetry(charging = false, chargerConnected = true, chargingStatus = "Paused", remoteChargingAction = "stop")
        assertTrue(ChargingControl.isActivelyCharging(old.mergeWith(ScooterTelemetry(charging = true))))
    }
}
