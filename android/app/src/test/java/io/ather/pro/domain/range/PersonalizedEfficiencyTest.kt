package io.ather.pro.domain.range

import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import org.junit.Assert.*
import org.junit.Test

class PersonalizedEfficiencyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60_000

    private fun trip(startMs: Long, km: Double, whPerKm: Double, id: String = "t-$startMs-$km") =
        TripRecord(
            id = id, startTimeMs = startMs, endTimeMs = startMs + 30 * 60_000,
            distanceKm = km, socConsumed = 4.0, energyConsumedWh = km * whPerKm,
            efficiencyWhPerKm = whPerKm, electricityCostInr = km * whPerKm / 1000.0 * 8.0,
            startOdoKm = 0.0, endOdoKm = km)

    @Test fun learnedEfficiencyIsDistanceWeighted() {
        val learned = PersonalizedEfficiency.fromTrips(listOf(
            trip(now - day, 10.0, 30.0),
            trip(now - 2 * day, 30.0, 40.0)
        ), now)
        requireNotNull(learned)
        assertEquals(37.5, learned.whPerKm, 0.001)
        assertEquals(40.0, learned.observedKm, 0.001)
        assertEquals(2, learned.sampleCount)
    }

    @Test fun noisyAndOutdatedTripsAreExcluded() {
        val learned = PersonalizedEfficiency.fromTrips(listOf(
            trip(now - day, 10.0, 30.0),
            trip(now - 40 * day, 50.0, 15.0),   // outside the 30-day window
            trip(now - day, 0.3, 20.0),         // too short
            trip(now - day, 10.0, 0.0),         // official ride without efficiency
            trip(now - day, 5.0, 4.0),          // below the physical floor
            trip(now - day, 5.0, 400.0)         // above the physical ceiling
        ), now)
        requireNotNull(learned)
        assertEquals(30.0, learned.whPerKm, 0.001)
        assertEquals(10.0, learned.observedKm, 0.001)
        assertEquals(1, learned.sampleCount)
    }

    @Test fun noUsableTripsYieldsNoLearning() {
        assertNull(PersonalizedEfficiency.fromTrips(emptyList(), now))
        assertNull(PersonalizedEfficiency.fromTrips(listOf(trip(now - day, 0.2, 30.0)), now))
    }

    @Test fun personalRangeBlendsTowardLearnedValueByObservedDistance() {
        val telemetry = ScooterTelemetry(batterySoc = 50.0, rangeKm = 101.0)
        // 3240 Wh usable at 50% = 1620 Wh; at 40 Wh/km that is 40.5 km.
        val learned = PersonalizedEfficiency.LearnedEfficiency(
            whPerKm = 40.0, observedKm = 300.0, sampleCount = 8, windowDays = 30)
        // Weight caps at 0.7 after 300 km: 101 + (40.5 - 101) * 0.7 = 58.65
        assertEquals(58.65,
            PersonalizedEfficiency.currentRangeKm(telemetry, learned, ScooterModel.ATHER_450X_3_7)!!, 0.001)

        val early = learned.copy(observedKm = 30.0)  // weight 0.1
        assertEquals(94.95,
            PersonalizedEfficiency.currentRangeKm(telemetry, early, ScooterModel.ATHER_450X_3_7)!!, 0.001)
    }

    @Test fun withoutReportedRangeLearnedValueNeedsHistory() {
        val telemetry = ScooterTelemetry(batterySoc = 50.0)
        val learned = PersonalizedEfficiency.LearnedEfficiency(40.0, 300.0, 8, 30)
        assertEquals(40.5,
            PersonalizedEfficiency.currentRangeKm(telemetry, learned, ScooterModel.ATHER_450X_3_7)!!, 0.001)
        val thin = learned.copy(observedKm = 5.0)
        assertNull(PersonalizedEfficiency.currentRangeKm(telemetry, thin, ScooterModel.ATHER_450X_3_7))
    }

    @Test fun personalizationNeedsSocModelAndLearning() {
        val learned = PersonalizedEfficiency.LearnedEfficiency(40.0, 300.0, 8, 30)
        assertNull(PersonalizedEfficiency.currentRangeKm(null, learned, ScooterModel.ATHER_450X_3_7))
        assertNull(PersonalizedEfficiency.currentRangeKm(
            ScooterTelemetry(batterySoc = 50.0), null, ScooterModel.ATHER_450X_3_7))
        assertNull(PersonalizedEfficiency.currentRangeKm(
            ScooterTelemetry(batterySoc = 50.0), learned, null))
        assertNull(PersonalizedEfficiency.currentRangeKm(
            ScooterTelemetry(batterySoc = 140.0), learned, ScooterModel.ATHER_450X_3_7))
    }

    /** Uses the no-engine path of RangeEstimator (active mode missing from reported ranges). */
    @Test fun rangeEstimatorSubstitutesPersonalizedAnchor() {
        val telemetry = ScooterTelemetry(
            batterySoc = 50.0, rangeKm = 101.0, mode = "Ride",
            modeRanges = mapOf("SmartEco" to io.ather.pro.domain.model.ModeRange(predictedRangeKm = 150.0)))
        val learned = PersonalizedEfficiency.LearnedEfficiency(40.0, 300.0, 8, 30)

        val personalized = RangeEstimator.current(telemetry, ScooterModel.ATHER_450X_3_7, learned)
        assertEquals(58.65, personalized!!, 0.001)

        val generic = RangeEstimator.current(telemetry, ScooterModel.ATHER_450X_3_7)
        assertEquals(101.0, generic!!, 0.001)

        val tiles = RangeEstimator.modes(telemetry, ScooterModel.ATHER_450X_3_7, learned)
        assertEquals(listOf("Ride"), tiles.map { it.name })
        assertEquals(58.65, tiles.single().km, 0.001)
        assertTrue(tiles.single().active)
    }
}
