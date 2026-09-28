package io.ather.pro.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RideAnalyticsAggregatorTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun emptyTrips_produceEmptyChartsAndZeroTotals() {
        val snapshot = RideAnalyticsAggregator.aggregate(emptyList(), utc)

        assertEquals(0, snapshot.totals.tripCount)
        assertEquals(0.0, snapshot.totals.totalDistanceKm, 0.0)
        assertNull(snapshot.totals.averageEfficiencyWhPerKm)
        assertTrue(snapshot.weeklyDistanceKm.isEmpty)
        assertTrue(snapshot.weeklyEnergyWh.isEmpty)
        assertTrue(snapshot.weeklyCostInr.isEmpty)
        assertTrue(snapshot.weeklyEfficiencyWhPerKm.isEmpty)
        assertTrue(snapshot.timeOfDayDistanceKm.isEmpty)
        assertTrue(snapshot.modeDistanceKm.isEmpty)
        assertEquals("Ride history has no mode field", snapshot.modeDistanceKm.sourceLabel)
    }

    @Test
    fun aggregatesDistanceEnergyCostAndWeightedEfficiency() {
        val trips = listOf(
            trip(
                id = "a",
                start = ms(2026, 9, 1, 8),
                distanceKm = 10.0,
                energyWh = 300.0,
                cost = 2.4,
                efficiency = 30.0,
                official = true
            ),
            trip(
                id = "b",
                start = ms(2026, 9, 1, 18),
                distanceKm = 20.0,
                energyWh = 400.0,
                cost = 3.2,
                efficiency = 20.0,
                official = false
            )
        )

        val snapshot = RideAnalyticsAggregator.aggregate(trips, utc)
        val totals = snapshot.totals

        assertEquals(2, totals.tripCount)
        assertEquals(30.0, totals.totalDistanceKm, 0.001)
        assertEquals(700.0, totals.totalEnergyWh, 0.001)
        assertEquals(5.6, totals.totalCostInr, 0.001)
        assertEquals(23.3, totals.averageEfficiencyWhPerKm!!, 0.05)
        assertEquals(1, totals.officialTripCount)
        assertEquals(1, totals.localTripCount)
        assertEquals("Room + official Ather rides", totals.sourceLabel)
    }

    @Test
    fun weeklyAndTimeOfDayBucketsAreDeterministic() {
        val trips = listOf(
            trip(id = "mon-am", start = ms(2026, 9, 7, 9), distanceKm = 5.0, energyWh = 100.0, cost = 0.8),
            trip(id = "mon-pm", start = ms(2026, 9, 7, 18), distanceKm = 7.0, energyWh = 140.0, cost = 1.1),
            trip(id = "next-week", start = ms(2026, 9, 14, 9), distanceKm = 3.0, energyWh = 90.0, cost = 0.7)
        )

        val snapshot = RideAnalyticsAggregator.aggregate(trips, utc)

        assertEquals(2, snapshot.weeklyDistanceKm.points.size)
        assertEquals(12.0, snapshot.weeklyDistanceKm.points[0].value, 0.001)
        assertEquals(3.0, snapshot.weeklyDistanceKm.points[1].value, 0.001)

        val hours = snapshot.timeOfDayDistanceKm.points.associate { it.key to it.value }
        assertEquals(8.0, hours["9"]!!, 0.001)
        assertEquals(7.0, hours["18"]!!, 0.001)
    }

    @Test
    fun modeSeriesOnlyWhenModePresent() {
        val withoutMode = listOf(
            trip(id = "x", start = ms(2026, 9, 1, 10), distanceKm = 4.0, energyWh = 80.0, cost = 0.6)
        )
        assertTrue(RideAnalyticsAggregator.aggregate(withoutMode, utc).modeDistanceKm.isEmpty)

        val withMode = listOf(
            trip(id = "eco", start = ms(2026, 9, 1, 10), distanceKm = 4.0, energyWh = 80.0, cost = 0.6, mode = "Eco"),
            trip(id = "sport", start = ms(2026, 9, 1, 12), distanceKm = 6.0, energyWh = 180.0, cost = 1.4, mode = "Sport"),
            trip(id = "eco2", start = ms(2026, 9, 1, 14), distanceKm = 2.0, energyWh = 40.0, cost = 0.3, mode = "Eco")
        )
        val modeSeries = RideAnalyticsAggregator.aggregate(withMode, utc).modeDistanceKm
        assertEquals(2, modeSeries.points.size)
        assertEquals("Eco", modeSeries.points[0].label)
        assertEquals(6.0, modeSeries.points[0].value, 0.001)
        assertEquals("Sport", modeSeries.points[1].label)
        assertEquals(6.0, modeSeries.points[1].value, 0.001)
    }

    private fun trip(
        id: String,
        start: Long,
        distanceKm: Double,
        energyWh: Double,
        cost: Double,
        efficiency: Double = if (distanceKm > 0) energyWh / distanceKm else 0.0,
        official: Boolean = true,
        mode: String? = null
    ): AnalyticsTrip = AnalyticsTrip(
        id = id,
        startTimeMs = start,
        endTimeMs = start + 3_600_000L,
        distanceKm = distanceKm,
        energyConsumedWh = energyWh,
        efficiencyWhPerKm = efficiency,
        electricityCostInr = cost,
        isOfficialRide = official,
        mode = mode
    )

    /** UTC millis for Y-M-D H:00. */
    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long {
        val cal = java.util.Calendar.getInstance(utc, java.util.Locale.US)
        cal.clear()
        cal.set(year, month - 1, day, hour, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
