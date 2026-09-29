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
    @Test fun stopRetriesRequireFreshReadingsAndBackOffAfterThreeAttempts() {
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
        val nextRetryAt = state.lastAttemptMs!! + ChargeLimitController.SLOW_RETRY_DELAY_MS
        assertEquals(ChargeLimitController.Decision.None,
            ChargeLimitController.onTelemetry(state, active, nextRetryAt - 1, nextRetryAt - 1))
        val fourth = ChargeLimitController.onTelemetry(state, active, nextRetryAt, nextRetryAt) as ChargeLimitController.Decision.RequestStop
        assertEquals(4, fourth.next.attempts)
    }
    @Test fun aFreshChargingFlagOverridesAnOldPausedStatus() {
        val old = ScooterTelemetry(charging = false, chargerConnected = true, chargingStatus = "Paused", remoteChargingAction = "stop")
        assertTrue(ChargingControl.isActivelyCharging(old.mergeWith(ScooterTelemetry(charging = true))))
    }

    @Test fun aFreshBatteryOverLimitStillStopsWhenChargingStatusIsSparse() {
        val state = ChargeLimitController.applySettings(ChargeLimitController.Snapshot(), true, 70)
        val evidence = ChargingEvidence().observe(active.copy(batterySoc = 69.0), 1_000)
            .observe(ScooterTelemetry(batterySoc = 72.0), 61_000)
        assertTrue(ChargeLimitController.onTelemetry(state, active, evidence.batteryAt, 61_000,
            chargingUpdatedMs = evidence.chargingAt) is ChargeLimitController.Decision.RequestStop)
        assertEquals(ChargeLimitController.Decision.None,
            ChargeLimitController.onTelemetry(state, active, 150_000, 150_000, chargingUpdatedMs = 1_000))
    }

    @Test fun delayedPhysicalPauseCanConfirmAfterTimeoutWithoutAnotherRequest() {
        val initial = ChargeLimitController.applySettings(ChargeLimitController.Snapshot(), true, 70)
        val pending = (ChargeLimitController.onTelemetry(initial, active, 1_000, 1_000) as ChargeLimitController.Decision.RequestStop).next
        val error = (ChargeLimitController.onTelemetry(pending, active, 1_000, 46_000) as ChargeLimitController.Decision.StateOnly).next
        val confirmed = (ChargeLimitController.onTelemetry(error, active.copy(charging = false, chargingStatus = "Paused"),
            50_000, 50_000) as ChargeLimitController.Decision.StateOnly).next
        assertEquals(ChargeLimitController.Status.CONFIRMED, confirmed.status)
    }
}
