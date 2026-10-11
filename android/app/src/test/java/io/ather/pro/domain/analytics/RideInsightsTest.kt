package io.ather.pro.domain.analytics

import io.ather.pro.domain.model.TripRecord
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class RideInsightsTest {

    private val zone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            set(year, month - 1, day, hour, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 2026-10-10 is a Saturday; the Monday-first week starts 2026-10-05. */
    private val now = at(2026, 10, 10, 18)

    private fun trip(
        start: Long, durationMin: Long, km: Double, whPerKm: Double, costInr: Double,
        id: String = "t-$start"
    ) = TripRecord(
        id = id, startTimeMs = start, endTimeMs = start + durationMin * 60_000,
        distanceKm = km, socConsumed = 5.0, energyConsumedWh = km * whPerKm,
        efficiencyWhPerKm = whPerKm, electricityCostInr = costInr,
        startOdoKm = 0.0, endOdoKm = km
    )

    @Test fun weekWindowStartsOnMonday() {
        assertEquals(at(2026, 10, 5, 0),
            RideInsights.windowStartMs(RideInsights.Window.WEEK, now, zone))
    }

    @Test fun sundayBelongsToTheWeekStartedSixDaysEarlier() {
        val sunday = at(2026, 10, 11, 9)
        assertEquals(at(2026, 10, 5, 0),
            RideInsights.windowStartMs(RideInsights.Window.WEEK, sunday, zone))
    }

    @Test fun monthWindowStartsOnTheFirst() {
        assertEquals(at(2026, 10, 1, 0),
            RideInsights.windowStartMs(RideInsights.Window.MONTH, now, zone))
    }

    @Test fun summarizeTotalsOnlyRidesInsideTheWindow() {
        val rides = listOf(
            trip(at(2026, 10, 6, 9), 30, 10.0, 30.0, 2.40),
            trip(at(2026, 10, 9, 20), 45, 15.0, 32.0, 3.84),
            trip(at(2026, 9, 20, 12), 30, 40.0, 30.0, 9.60),   // last month
            trip(at(2026, 10, 3, 12), 30, 8.0, 30.0, 1.92)     // Saturday, before this Monday
        )
        val week = RideInsights.summarize(
            rides, RideInsights.Window.WEEK, now,
            petrolPricePerLitre = 103.0, timeZone = zone)
        assertEquals(2, week.rideCount)
        assertEquals(25.0, week.distanceKm, 0.001)
        assertEquals(75, week.durationMinutes)
        assertEquals(6.24, week.electricityCostInr, 0.001)
        // 25 km / 45 km per litre * ₹103 = ₹57.22 of petrol; saved = 57.22 - 6.24
        assertEquals(57.22, week.petrolEquivalentCostInr, 0.001)
        assertEquals(50.98, week.savingsInr, 0.001)
        // The September ride is invisible to the week window…
        val month = RideInsights.summarize(
            rides, RideInsights.Window.MONTH, now,
            petrolPricePerLitre = 103.0, timeZone = zone)
        assertEquals(3, month.rideCount)
        assertEquals(33.0, month.distanceKm, 0.001)
    }

    @Test fun bestEfficiencyIgnoresUnknownAndTooShortRides() {
        val rides = listOf(
            trip(at(2026, 10, 6, 9), 30, 12.0, 34.0, 3.26),              // ordinary
            trip(at(2026, 10, 7, 9), 30, 8.0, 27.5, 2.20),               // best
            trip(at(2026, 10, 8, 9), 30, 6.0, 0.0, 1.44),                // official ride without efficiency
            trip(at(2026, 10, 9, 9), 10, 0.4, 12.0, 0.10)                // too short to be meaningful
        )
        val week = RideInsights.summarize(
            rides, RideInsights.Window.WEEK, now,
            petrolPricePerLitre = 103.0, timeZone = zone)
        assertEquals(27.5, week.bestEfficiencyWhPerKm!!, 0.001)
        assertEquals(at(2026, 10, 7, 9), week.bestEfficiencyRideStartMs)
        assertEquals(8.0, week.bestEfficiencyDistanceKm!!, 0.001)
    }

    @Test fun windowWithoutRidesYieldsZerosAndNoBest() {
        val summary = RideInsights.summarize(
            emptyList(), RideInsights.Window.WEEK, now,
            petrolPricePerLitre = 103.0, timeZone = zone)
        assertEquals(0, summary.rideCount)
        assertEquals(0.0, summary.distanceKm, 0.001)
        assertEquals(0.0, summary.savingsInr, 0.001)
        assertNull(summary.bestEfficiencyWhPerKm)
    }

    @Test fun petrolAssumptionZeroDisablesPetrolMath() {
        val summary = RideInsights.summarize(
            listOf(trip(at(2026, 10, 6, 9), 30, 10.0, 30.0, 2.40)),
            RideInsights.Window.WEEK, now,
            petrolPricePerLitre = 103.0, timeZone = zone, petrolBikeKmPerLitre = 0.0)
        assertEquals(0.0, summary.petrolEquivalentCostInr, 0.001)
        assertEquals(-2.40, summary.savingsInr, 0.001)
    }
}
