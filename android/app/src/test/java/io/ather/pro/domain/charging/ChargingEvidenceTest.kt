package io.ather.pro.domain.charging

import io.ather.pro.domain.model.GpsData
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class ChargingEvidenceTest {
    private val active = ScooterTelemetry(batterySoc = 85.0, charging = true, chargerConnected = true)
    private val limit = ChargeLimitController.applySettings(ChargeLimitController.Snapshot(), true, 80)

    @Test fun gpsPacketsDoNotRefreshOldBatteryOrCharging() {
        val evidence = ChargingEvidence().observe(active, 1_000)
            .observe(ScooterTelemetry(gps = GpsData(latitude = 12.0, longitude = 77.0)), 50_000)
        assertEquals(1_000L, evidence.completeAt)
        assertEquals(ChargeLimitController.Decision.None,
            ChargeLimitController.onTelemetry(limit, active, evidence.completeAt, 50_000, chargingUpdatedMs = evidence.chargingAt))
    }

    @Test fun sparseBatteryAndChargingPacketsBothMustBeFresh() {
        var evidence = ChargingEvidence().observe(ScooterTelemetry(batterySoc = 85.0), 1_000)
        assertNull(evidence.completeAt)
        evidence = evidence.observe(ScooterTelemetry(charging = true), 2_000)
        assertTrue(ChargeLimitController.onTelemetry(limit, active, evidence.completeAt, 2_000) is ChargeLimitController.Decision.RequestStop)
        evidence = evidence.observe(ScooterTelemetry(charging = true), 90_000)
        assertEquals(ChargeLimitController.Decision.None, ChargeLimitController.onTelemetry(limit, active, evidence.completeAt, 90_000))
    }

    @Test fun pluggedInOrStartEchoIsNotProofOfCharging() {
        val evidence = ChargingEvidence().observe(ScooterTelemetry(chargerConnected = true, remoteChargingAction = "start"), 1_000)
        assertNull(evidence.chargingAt)
    }

    @Test fun nullOrOldTelemetryCannotConfirmPendingStop() {
        val pending = (ChargeLimitController.onTelemetry(limit, active, 1_000, 1_000) as ChargeLimitController.Decision.RequestStop).next
        assertEquals(ChargeLimitController.Decision.None, ChargeLimitController.onTelemetry(pending, null, 2_000, 2_000))
        val stopped = active.copy(charging = false)
        assertEquals(ChargeLimitController.Decision.None,
            ChargeLimitController.onTelemetry(pending, stopped, 2_000, 2_000, chargingUpdatedMs = 500))
        assertTrue(ChargeLimitController.onTelemetry(pending, stopped, null, 2_000, chargingUpdatedMs = 2_000) is ChargeLimitController.Decision.StateOnly)
    }

    @Test fun restoringPendingWithoutTelemetryTimesOutWithoutResending() {
        val pending = (ChargeLimitController.onTelemetry(limit, active, 1_000, 1_000) as ChargeLimitController.Decision.RequestStop).next
        val restored = pending.copy()
        val decision = ChargeLimitController.onTelemetry(restored, null, null, 100_000) as ChargeLimitController.Decision.StateOnly
        assertEquals(ChargeLimitController.Status.ERROR, decision.next.status)
        assertFalse(decision.next.armed)
    }

    @Test fun identicalSettingsPreservePendingAndConfirmedLatch() {
        val pending = (ChargeLimitController.onTelemetry(limit, active, 1_000, 1_000) as ChargeLimitController.Decision.RequestStop).next
        assertEquals(pending, ChargeLimitController.applySettings(pending, true, 80))
        val confirmed = pending.copy(status = ChargeLimitController.Status.CONFIRMED, pendingSinceMs = null)
        assertEquals(confirmed, ChargeLimitController.applySettings(confirmed, true, 80))
        assertTrue(ChargeLimitController.applySettings(confirmed, true, 90).armed)
    }

    @Test fun unknownOrStaleUnplugCannotRearmAnError() {
        val error = limit.copy(status = ChargeLimitController.Status.ERROR, armed = false)
        assertEquals(ChargeLimitController.Decision.None, ChargeLimitController.onTelemetry(error, null, null, 1_000))
        assertEquals(ChargeLimitController.Decision.None,
            ChargeLimitController.onTelemetry(error, ScooterTelemetry(chargerConnected = false), 1_000, 100_000))
    }

    @Test fun invalidSocNeverDispatchesAStop() {
        for (soc in listOf(Double.NaN, Double.POSITIVE_INFINITY, 101.0, -1.0)) {
            assertEquals(ChargeLimitController.Decision.None,
                ChargeLimitController.onTelemetry(limit, active.copy(batterySoc = soc), 1_000, 1_000))
        }
    }

    @Test fun unplugDeltaClearsStaleChargingAndCanConfirm() {
        val merged = active.mergeWith(ScooterTelemetry(chargerConnected = false))
        assertFalse(ChargingControl.isActivelyCharging(merged))
        assertFalse(ChargingControl.isPluggedIn(merged))
    }
}
