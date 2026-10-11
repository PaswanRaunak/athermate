package io.ather.pro.domain.analytics

import io.ather.pro.domain.model.TripRecord
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Calendar-window riding summaries over stored trips (local auto-detected + synced
 * cloud rides). Presentation-friendly math only; never invents rides or efficiency.
 */
object RideInsights {

    enum class Window { WEEK, MONTH }

    /**
     * Typical commuter motorcycle mileage used to price the same distance in petrol.
     * An assumption by necessity — state it wherever the savings figure is shown.
     */
    const val DEFAULT_PETROL_BIKE_KM_PER_LITRE = 45.0

    /** Trips shorter than this are too noisy to crown as "most efficient". */
    const val BEST_RIDE_MIN_KM = 1.0

    data class Summary(
        val window: Window,
        val windowStartMs: Long,
        val rideCount: Int,
        val distanceKm: Double,
        val durationMinutes: Long,
        val electricityCostInr: Double,
        val petrolEquivalentCostInr: Double,
        val savingsInr: Double,
        val bestEfficiencyWhPerKm: Double? = null,
        val bestEfficiencyRideStartMs: Long? = null,
        val bestEfficiencyDistanceKm: Double? = null
    )

    fun summarize(
        trips: List<TripRecord>,
        window: Window,
        nowMs: Long,
        petrolPricePerLitre: Double,
        timeZone: TimeZone = TimeZone.getDefault(),
        petrolBikeKmPerLitre: Double = DEFAULT_PETROL_BIKE_KM_PER_LITRE
    ): Summary {
        val windowStartMs = windowStartMs(window, nowMs, timeZone)
        val inWindow = trips.filter { it.startTimeMs >= windowStartMs && it.startTimeMs <= nowMs }
        val distanceKm = inWindow.sumOf { it.distanceKm }
        val electricityCostInr = inWindow.sumOf { it.electricityCostInr }
        val petrolCostInr = if (petrolBikeKmPerLitre > 0.0 && petrolPricePerLitre.isFinite()) {
            distanceKm / petrolBikeKmPerLitre * petrolPricePerLitre
        } else 0.0
        val best = inWindow
            .filter { it.distanceKm >= BEST_RIDE_MIN_KM && it.efficiencyWhPerKm > 0.0 }
            .minByOrNull { it.efficiencyWhPerKm }
        return Summary(
            window = window,
            windowStartMs = windowStartMs,
            rideCount = inWindow.size,
            distanceKm = distanceKm.round2(),
            durationMinutes = inWindow.sumOf { (it.endTimeMs - it.startTimeMs).coerceAtLeast(0L) } / 60_000,
            electricityCostInr = electricityCostInr.round2(),
            petrolEquivalentCostInr = petrolCostInr.round2(),
            savingsInr = (petrolCostInr - electricityCostInr).round2(),
            bestEfficiencyWhPerKm = best?.efficiencyWhPerKm?.round1(),
            bestEfficiencyRideStartMs = best?.startTimeMs,
            bestEfficiencyDistanceKm = best?.distanceKm?.round2()
        )
    }

    /** Monday-first calendar windows, matching RideAnalyticsAggregator's week bucketing. */
    fun windowStartMs(window: Window, nowMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone, Locale.US)
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        when (window) {
            Window.WEEK -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val offset = when (dayOfWeek) {
                    Calendar.MONDAY -> 0
                    Calendar.SUNDAY -> -6
                    else -> Calendar.MONDAY - dayOfWeek
                }
                cal.add(Calendar.DAY_OF_MONTH, offset)
            }
            Window.MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun Double.round1() = (this * 10).roundToInt() / 10.0
    private fun Double.round2() = (this * 100).roundToInt() / 100.0
}
