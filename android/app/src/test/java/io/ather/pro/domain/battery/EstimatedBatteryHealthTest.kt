package io.ather.pro.domain.battery

import io.ather.pro.domain.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatedBatteryHealthTest {

    private fun trip(
        id: String,
        distanceKm: Double,
        energyWh: Double,
        soc: Double,
        isOfficial: Boolean = false
    ) = TripRecord(
        id = id,
        startTimeMs = 1_000L,
        endTimeMs = 2_000L,
        distanceKm = distanceKm,
        socConsumed = soc,
        energyConsumedWh = energyWh,
        efficiencyWhPerKm = if (distanceKm > 0) energyWh / distanceKm else 0.0,
        electricityCostInr = 0.0,
        startOdoKm = 100.0,
        endOdoKm = 100.0 + distanceKm,
        isOfficialRide = isOfficial
    )

    @Test
    fun prefersReportedBmsOverEstimate() {
        val display = EstimatedBatteryHealth.resolve(
            reportedBmsSohPercent = 91.0,
            trips = emptyList(),
            nominalCapacityWh = 2_900.0
        )
        assertTrue(display is EstimatedBatteryHealth.Display.ReportedBms)
        assertEquals(91.0, (display as EstimatedBatteryHealth.Display.ReportedBms).percent, 0.01)
    }

    @Test
    fun insufficientSamples_unavailable() {
        val trips = (1..3).map {
            trip("t$it", distanceKm = 10.0, energyWh = 250.0, soc = 10.0)
        }
        val display = EstimatedBatteryHealth.estimateFromTrips(trips, nominalCapacityWh = 2_900.0)
        assertEquals(EstimatedBatteryHealth.Display.Unavailable, display)
    }

    @Test
    fun enoughSamples_estimatesWithConfidenceAndMethod() {
        // Official reference = 20 Wh/km. Local observation = 12.5 km / 10% SoC,
        // so implied pack = 12.5*20/0.10 = 2500 Wh → ≈86.2% of 2900 Wh.
        val official = (1..3).map {
            trip("o$it", distanceKm = 10.0, energyWh = 200.0, soc = 0.0, isOfficial = true)
        }
        val local = (1..6).map {
            trip("t$it", distanceKm = 12.5, energyWh = 290.0, soc = 10.0)
        }
        val trips = official + local
        val display = EstimatedBatteryHealth.estimateFromTrips(trips, nominalCapacityWh = 2_900.0)
        assertTrue(display is EstimatedBatteryHealth.Display.Estimated)
        val est = display as EstimatedBatteryHealth.Display.Estimated
        assertEquals(6, est.sampleCount)
        assertTrue(est.percent in 80.0..95.0)
        assertTrue(est.confidenceLabel.contains("confidence"))
        assertTrue(est.methodLabel.contains("Cloud ride efficiency"))
    }

    @Test
    fun kmPerUnit_fromRealDistanceAndEnergyOnly() {
        // 20 km / 0.5 kWh = 40 km/unit (unit = kWh)
        val t = trip("a", distanceKm = 20.0, energyWh = 500.0, soc = 15.0)
        assertEquals(40.0, t.kmPerUnitOrNull()!!, 0.01)
        assertEquals(
            40.0,
            rideEfficiencyKmPerUnit(listOf(t, trip("b", 10.0, 250.0, 8.0)))!!,
            0.01
        )
        assertEquals(null, trip("c", 0.0, 100.0, 5.0).kmPerUnitOrNull())
    }

    @Test
    fun neverFabricatesWhenEnergyMissing() {
        val trips = (1..8).map {
            trip("t$it", distanceKm = 10.0, energyWh = 0.0, soc = 12.0)
        }
        assertEquals(
            EstimatedBatteryHealth.Display.Unavailable,
            EstimatedBatteryHealth.estimateFromTrips(trips, 2_900.0)
        )
    }

    @Test
    fun officialRidesWithDerivedSoc_doNotFabricateHealth() {
        val official = (1..8).map {
            trip("o$it", distanceKm = 12.0, energyWh = 300.0, soc = 10.0, isOfficial = true)
        }
        assertEquals(
            EstimatedBatteryHealth.Display.Unavailable,
            EstimatedBatteryHealth.estimateFromTrips(official, 3_000.0)
        )
    }

    @Test
    fun localNominalDerivedEnergy_withoutOfficialReference_isUnavailable() {
        val circularLocal = (1..8).map {
            // 300 Wh is exactly 10% of 3000 Wh; the estimator must not turn this
            // circular local energy value into a fake 100% health result.
            trip("c$it", distanceKm = 12.0, energyWh = 300.0, soc = 10.0)
        }
        assertEquals(
            EstimatedBatteryHealth.Display.Unavailable,
            EstimatedBatteryHealth.estimateFromTrips(circularLocal, 3_000.0)
        )
    }
}
