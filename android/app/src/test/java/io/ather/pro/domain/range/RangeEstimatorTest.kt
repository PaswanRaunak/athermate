package io.ather.pro.domain.range

import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class RangeEstimatorTest {
    @Test fun calculatesTargetEnergyCostRangeAndEtaFromReportedData() {
        val telemetry = ScooterTelemetry(batterySoc = 50.0, rangeKm = 40.0, timeToEightyChargeMin = 60.0, timeToFullChargeMin = 150.0)
        val result = RangeEstimator.target(telemetry, 80, 3_240.0, 8.0)!!
        assertEquals(0.972, result.energyKWh, 0.0001)
        assertEquals(7.776, result.costInr, 0.0001)
        assertEquals(64.0, result.rangeAtTargetKm!!, 0.001)
        assertEquals(60.0, result.minutesToTarget!!, 0.001)
    }

    @Test fun aboveTargetHasNoRemainingChargeOrCost() {
        val result = RangeEstimator.target(ScooterTelemetry(batterySoc = 90.0), 80, 3_240.0, 8.0)!!
        assertEquals(0.0, result.energyKWh, 0.0)
        assertEquals(0.0, result.costInr, 0.0)
        assertEquals(0.0, result.minutesToTarget!!, 0.0)
    }

    @Test fun unknownDataDoesNotInventRangeOrEta() {
        assertNull(RangeEstimator.target(null, 80, 3_240.0, 8.0))
        val result = RangeEstimator.target(ScooterTelemetry(batterySoc = 50.0), 80, 3_240.0, 8.0)!!
        assertNull(result.rangeAtTargetKm)
        assertNull(result.minutesToTarget)
        assertNull(RangeEstimator.current(ScooterTelemetry(modeRanges = mapOf("Ride" to ModeRange(rawRangeKm = 80.0)))))
    }

    @Test fun modesHandleAliasesInvalidRangesAndUnusualModels() {
        val telemetry = ScooterTelemetry(mode = "smart_eco", modeRanges = mapOf(
            "SmartEco" to ModeRange(predictedRangeKm = Double.NaN, rawRangeKm = 65.0),
            "Warp+" to ModeRange(predictedRangeKm = 40.0), "Sport" to ModeRange(rawRangeKm = -1.0)))
        val modes = RangeEstimator.modes(telemetry)
        assertEquals(listOf("SmartEco", "Warp+"), modes.map { it.name })
        assertTrue(modes.first().active)
        assertEquals(65.0, RangeEstimator.current(telemetry)!!, 0.0)
    }

    @Test fun zeroBatteryDoesNotDivideByZeroAndBadInputsAreRejected() {
        assertNull(RangeEstimator.target(ScooterTelemetry(batterySoc = 0.0, rangeKm = 0.0), 80, 3_240.0, 8.0)!!.rangeAtTargetKm)
        assertNull(RangeEstimator.target(ScooterTelemetry(batterySoc = Double.NaN), 80, 3_240.0, 8.0))
        assertNull(RangeEstimator.target(ScooterTelemetry(batterySoc = 50.0), 80, 3_240.0, -1.0))
    }
}
