package io.ather.pro.domain.range

import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.ScooterModel
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
        val modes = RangeEstimator.modes(telemetry, ScooterModel.ATHER_APEX)
        assertEquals(listOf("SmartEco", "Warp+"), modes.map { it.name })
        assertTrue(modes.first().active)
        assertEquals(65.0, RangeEstimator.current(telemetry)!!, 0.0)
    }

    @Test fun zeroBatteryDoesNotDivideByZeroAndBadInputsAreRejected() {
        assertNull(RangeEstimator.target(ScooterTelemetry(batterySoc = 0.0, rangeKm = 0.0), 80, 3_240.0, 8.0)!!.rangeAtTargetKm)
        assertNull(RangeEstimator.target(ScooterTelemetry(batterySoc = Double.NaN), 80, 3_240.0, 8.0))
        assertNull(RangeEstimator.target(ScooterTelemetry(batterySoc = 50.0), 80, 3_240.0, -1.0))
    }

    @Test fun thirtyPercentReportsRemainingRangeWithoutScalingItAgain() {
        val telemetry = ScooterTelemetry(batterySoc = 30.0, mode = "Ride", modeRanges = mapOf(
            "SmartEco" to ModeRange(predictedRangeKm = 39.0),
            "Eco" to ModeRange(rawRangeKm = 37.5),
            "Ride" to ModeRange(predictedRangeKm = 31.5),
            "Sport" to ModeRange(rawRangeKm = 28.5),
            "Warp" to ModeRange(rawRangeKm = 25.5),
            "WarpPlus" to ModeRange(rawRangeKm = 24.0)))
        val modes = RangeEstimator.modes(telemetry, ScooterModel.ATHER_450X_3_7)
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport", "Warp"), modes.map { it.name })
        assertEquals(listOf(39.0, 37.5, 31.5, 28.5, 25.5), modes.map { it.km })
        assertEquals(31.5, RangeEstimator.current(telemetry, ScooterModel.ATHER_450X_3_7)!!, 0.0)
    }

    @Test fun warpPlusIsOnlyShownOnApexAndNeverMarksWarpAsActive() {
        val telemetry = ScooterTelemetry(mode = "warp+", modeRanges = mapOf(
            "Warp" to ModeRange(rawRangeKm = 30.0),
            "Warp+" to ModeRange(rawRangeKm = 25.0),
            "warp_plus" to ModeRange(predictedRangeKm = 24.0)))
        val apex = RangeEstimator.modes(telemetry, ScooterModel.ATHER_APEX).single()
        assertEquals("Warp+", apex.name)
        assertEquals(24.0, apex.km, 0.0)
        assertTrue(apex.active)
        for (model in ScooterModel.entries.filter { it != ScooterModel.ATHER_APEX }) {
            assertFalse(RangeEstimator.modes(telemetry, model).any { it.name == "Warp+" || it.active })
            assertNull(RangeEstimator.current(telemetry, model))
        }
        assertFalse(RangeEstimator.modes(telemetry).any { it.name == "Warp+" })
    }

    @Test fun modelsExcludeUnsupportedModesAndKeepRiztaZip() {
        val telemetry = ScooterTelemetry(mode = "zip", modeRanges = RideMode.entries.associate {
            it.apiName to ModeRange(rawRangeKm = 30.0)
        })
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport"),
            RangeEstimator.modes(telemetry, ScooterModel.ATHER_450S).map { it.name })
        for (model in listOf(ScooterModel.ATHER_RIZTA_3_7, ScooterModel.ATHER_RIZTA_2_9)) {
            val modes = RangeEstimator.modes(telemetry, model)
            assertEquals(listOf("SmartEco", "Eco", "Zip"), modes.map { it.name })
            assertEquals("Zip", modes.single { it.active }.name)
        }
    }

    @Test fun duplicateAliasesProduceOneTileAndMissingModesAreNotInvented() {
        val telemetry = ScooterTelemetry(mode = "smart eco", modeRanges = mapOf(
            "smart_eco" to ModeRange(rawRangeKm = 28.0),
            "SmartEco" to ModeRange(predictedRangeKm = 31.0),
            "mystery" to ModeRange(rawRangeKm = 999.0)))
        val result = RangeEstimator.modes(telemetry, ScooterModel.ATHER_450X_2_9).single()
        assertEquals(RideModeRange("SmartEco", 31.0, true), result)
    }

    @Test fun seventyNinePercentAnd101KmSmartEcoAnchorsEveryModeToLiveRange() {
        val telemetry = ScooterTelemetry(batterySoc = 79.0, rangeKm = 101.0, mode = "smart_eco", modeRanges = mapOf(
            "SmartEco" to ModeRange(predictedRangeKm = 130.0),
            "Eco" to ModeRange(predictedRangeKm = 125.0),
            "Ride" to ModeRange(predictedRangeKm = 105.0),
            "Sport" to ModeRange(predictedRangeKm = 95.0),
            "Warp" to ModeRange(predictedRangeKm = 85.0)))
        val modes = RangeEstimator.modes(telemetry, ScooterModel.ATHER_450X_3_7).associateBy { it.name }
        assertEquals(101.0, modes.getValue("SmartEco").km, 0.0)
        assertEquals(101.0 * 105.0 / 130.0, modes.getValue("Ride").km, 0.001)
        assertEquals(101.0 * 85.0 / 130.0, modes.getValue("Warp").km, 0.001)
    }

    @Test fun knownLiveModeRemainsAvailableWithoutSeparateModeReferences() {
        val modes = RangeEstimator.modes(ScooterTelemetry(batterySoc = 79.0, rangeKm = 101.0, mode = "SmartEco",
            modeRanges = mapOf("Ride" to ModeRange(predictedRangeKm = 105.0))),
            ScooterModel.ATHER_450X_3_7)
        assertEquals(listOf(RideModeRange("SmartEco", 101.0, true)), modes)
    }
}
