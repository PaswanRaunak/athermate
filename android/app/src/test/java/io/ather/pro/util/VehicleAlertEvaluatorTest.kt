package io.ather.pro.util

import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TpmsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleAlertEvaluatorTest {

    @Test
    fun charging_suppressesMilestonesAlreadyReachedAtPlugin() {
        val decision = VehicleAlertEvaluator.evaluateCharging(
            previous = VehicleAlertEvaluator.ChargingLatch(),
            isConnected = true,
            isCharging = true,
            soc = 85.0
        )
        assertTrue(decision.latch.sessionActive)
        assertTrue(decision.latch.notified80)
        assertFalse(decision.latch.notified100)
        assertFalse(decision.fire80)
        assertFalse(decision.fire100)
        assertTrue(decision.showProgress)
    }

    @Test
    fun charging_firesEightyOnceThenSuppressesDuplicates() {
        val first = VehicleAlertEvaluator.evaluateCharging(
            previous = VehicleAlertEvaluator.ChargingLatch(),
            isConnected = true,
            isCharging = true,
            soc = 70.0
        )
        assertFalse(first.fire80)

        val crossing = VehicleAlertEvaluator.evaluateCharging(
            previous = first.latch,
            isConnected = true,
            isCharging = true,
            soc = 80.5
        )
        assertTrue(crossing.fire80)

        val again = VehicleAlertEvaluator.evaluateCharging(
            previous = crossing.latch,
            isConnected = true,
            isCharging = true,
            soc = 81.0
        )
        assertFalse(again.fire80)
    }

    @Test
    fun charging_unplugResetsLatch() {
        val active = VehicleAlertEvaluator.ChargingLatch(
            sessionActive = true,
            notified80 = true,
            notified100 = true
        )
        val decision = VehicleAlertEvaluator.evaluateCharging(
            previous = active,
            isConnected = false,
            isCharging = false,
            soc = 100.0
        )
        assertFalse(decision.latch.sessionActive)
        assertFalse(decision.latch.notified80)
        assertTrue(decision.cancelProgress)
    }

    @Test
    fun tpms_noAlertWhenAccessoryAbsent() {
        val decision = VehicleAlertEvaluator.evaluateTpms(
            previous = VehicleAlertEvaluator.TpmsLatch(),
            tpms = null
        )
        assertFalse(decision.fireFrontLow)
        assertFalse(decision.fireRearLow)

        val empty = VehicleAlertEvaluator.evaluateTpms(
            previous = VehicleAlertEvaluator.TpmsLatch(frontLowLatched = true),
            tpms = TpmsData()
        )
        assertFalse(empty.fireFrontLow)
        assertFalse(empty.latch.frontLowLatched)
    }

    @Test
    fun tpms_firesLowOnceWithHysteresisRecovery() {
        val low = VehicleAlertEvaluator.evaluateTpms(
            previous = VehicleAlertEvaluator.TpmsLatch(),
            tpms = TpmsData(frontPressurePsi = 26.0, rearPressurePsi = 36.0)
        )
        assertTrue(low.fireFrontLow)
        assertFalse(low.fireRearLow)

        val stillLow = VehicleAlertEvaluator.evaluateTpms(
            previous = low.latch,
            tpms = TpmsData(frontPressurePsi = 25.0, rearPressurePsi = 36.0)
        )
        assertFalse(stillLow.fireFrontLow)

        val recovered = VehicleAlertEvaluator.evaluateTpms(
            previous = stillLow.latch,
            tpms = TpmsData(frontPressurePsi = 31.0, rearPressurePsi = 36.0)
        )
        assertFalse(recovered.latch.frontLowLatched)

        val dropsAgain = VehicleAlertEvaluator.evaluateTpms(
            previous = recovered.latch,
            tpms = TpmsData(frontPressurePsi = 27.0, rearPressurePsi = 36.0)
        )
        assertTrue(dropsAgain.fireFrontLow)
    }

    @Test
    fun isChargingConnected_fromTelemetryFlags() {
        val charging = ScooterTelemetry(charging = true, chargerConnected = false)
        assertEquals(true to true, VehicleAlertEvaluator.isChargingConnected(charging))

        val pluggedIdle = ScooterTelemetry(charging = false, chargerConnected = true)
        assertEquals(true to false, VehicleAlertEvaluator.isChargingConnected(pluggedIdle))
    }
}
