package io.ather.pro.domain.analytics

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Deterministic aggregation over observed trip history.
 * Never invents chart points when the input list is empty or a dimension is missing.
 */
object RideAnalyticsAggregator {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    fun aggregate(
        trips: List<AnalyticsTrip>,
        timeZone: TimeZone = utc
    ): RideAnalyticsSnapshot {
        val sourceLabel = when {
            trips.isEmpty() -> "No ride history"
            trips.any { it.isOfficialRide } && trips.any { !it.isOfficialRide } ->
                "Room + synced cloud rides"
            trips.all { it.isOfficialRide } -> "Synced cloud rides"
            else -> "Room trip history"
        }

        val totals = totals(trips, sourceLabel)
        return RideAnalyticsSnapshot(
            totals = totals,
            weeklyDistanceKm = weeklySeries(trips, timeZone, "weekly_distance", "Weekly distance", "km", sourceLabel) {
                it.distanceKm
            },
            weeklyEnergyWh = weeklySeries(trips, timeZone, "weekly_energy", "Weekly energy", "Wh", sourceLabel) {
                it.energyConsumedWh
            },
            weeklyCostInr = weeklySeries(trips, timeZone, "weekly_cost", "Weekly cost", "INR", sourceLabel) {
                it.electricityCostInr
            },
            weeklyEfficiencyWhPerKm = weeklyEfficiencySeries(trips, timeZone, sourceLabel),
            timeOfDayDistanceKm = timeOfDaySeries(trips, timeZone, sourceLabel),
            modeDistanceKm = modeSeries(trips, sourceLabel)
        )
    }

    fun totals(trips: List<AnalyticsTrip>, sourceLabel: String = "Ride history"): AnalyticsTotals {
        val totalDistance = trips.sumOf { it.distanceKm }
        val totalEnergy = trips.sumOf { it.energyConsumedWh }
        val weightedEfficiency = if (totalDistance > 0.0) {
            totalEnergy / totalDistance
        } else {
            null
        }
        return AnalyticsTotals(
            tripCount = trips.size,
            totalDistanceKm = round2(totalDistance),
            totalEnergyWh = round1(totalEnergy),
            totalCostInr = round2(trips.sumOf { it.electricityCostInr }),
            averageEfficiencyWhPerKm = weightedEfficiency?.let(::round1),
            officialTripCount = trips.count { it.isOfficialRide },
            localTripCount = trips.count { !it.isOfficialRide },
            sourceLabel = sourceLabel
        )
    }

    private fun weeklySeries(
        trips: List<AnalyticsTrip>,
        timeZone: TimeZone,
        id: String,
        title: String,
        unit: String,
        sourceLabel: String,
        valueOf: (AnalyticsTrip) -> Double
    ): ChartSeries {
        if (trips.isEmpty()) {
            return emptySeries(id, title, unit, sourceLabel)
        }
        val buckets = linkedMapOf<Long, MutableList<AnalyticsTrip>>()
        trips.forEach { trip ->
            val weekStart = weekStartMs(trip.startTimeMs, timeZone)
            buckets.getOrPut(weekStart) { mutableListOf() }.add(trip)
        }
        val points = buckets.entries
            .sortedBy { it.key }
            .map { (weekStart, weekTrips) ->
                ChartPoint(
                    key = weekStart.toString(),
                    label = weekLabel(weekStart, timeZone),
                    value = round2(weekTrips.sumOf(valueOf)),
                    sortKey = weekStart
                )
            }
            .filter { it.value > 0.0 }
        return ChartSeries(id, title, unit, sourceLabel, points)
    }

    private fun weeklyEfficiencySeries(
        trips: List<AnalyticsTrip>,
        timeZone: TimeZone,
        sourceLabel: String
    ): ChartSeries {
        val id = "weekly_efficiency"
        val title = "Weekly efficiency"
        val unit = "Wh/km"
        if (trips.isEmpty()) {
            return emptySeries(id, title, unit, sourceLabel)
        }
        val buckets = linkedMapOf<Long, MutableList<AnalyticsTrip>>()
        trips.forEach { trip ->
            val weekStart = weekStartMs(trip.startTimeMs, timeZone)
            buckets.getOrPut(weekStart) { mutableListOf() }.add(trip)
        }
        val points = buckets.entries
            .sortedBy { it.key }
            .mapNotNull { (weekStart, weekTrips) ->
                val distance = weekTrips.sumOf { it.distanceKm }
                val energy = weekTrips.sumOf { it.energyConsumedWh }
                if (distance <= 0.0) return@mapNotNull null
                ChartPoint(
                    key = weekStart.toString(),
                    label = weekLabel(weekStart, timeZone),
                    value = round1(energy / distance),
                    sortKey = weekStart
                )
            }
        return ChartSeries(id, title, unit, sourceLabel, points)
    }

    private fun timeOfDaySeries(
        trips: List<AnalyticsTrip>,
        timeZone: TimeZone,
        sourceLabel: String
    ): ChartSeries {
        val id = "tod_distance"
        val title = "Distance by hour of day"
        val unit = "km"
        if (trips.isEmpty()) {
            return emptySeries(id, title, unit, sourceLabel)
        }
        val byHour = DoubleArray(24)
        val cal = Calendar.getInstance(timeZone, Locale.US)
        trips.forEach { trip ->
            cal.timeInMillis = trip.startTimeMs
            byHour[cal.get(Calendar.HOUR_OF_DAY)] += trip.distanceKm
        }
        val points = byHour.withIndex().mapNotNull { (hour, distance) ->
            if (distance <= 0.0) null
            else ChartPoint(
                key = hour.toString(),
                label = String.format(Locale.US, "%02d:00", hour),
                value = round2(distance),
                sortKey = hour.toLong()
            )
        }
        return ChartSeries(id, title, unit, sourceLabel, points)
    }

    private fun modeSeries(trips: List<AnalyticsTrip>, sourceLabel: String): ChartSeries {
        val id = "mode_distance"
        val title = "Distance by ride mode"
        val unit = "km"
        val modes = trips.mapNotNull { trip ->
            trip.mode?.takeIf { it.isNotBlank() }?.let { mode -> mode to trip }
        }
        if (modes.isEmpty()) {
            return emptySeries(
                id = id,
                title = title,
                unit = unit,
                sourceLabel = "Ride history has no mode field"
            )
        }
        val buckets = linkedMapOf<String, Double>()
        modes.forEach { (mode, trip) ->
            buckets[mode] = (buckets[mode] ?: 0.0) + trip.distanceKm
        }
        val points = buckets.entries
            .sortedByDescending { it.value }
            .map { (mode, distance) ->
                ChartPoint(
                    key = mode,
                    label = mode,
                    value = round2(distance),
                    sortKey = 0L
                )
            }
            .filter { it.value > 0.0 }
        return ChartSeries(id, title, unit, sourceLabel, points)
    }

    private fun emptySeries(
        id: String,
        title: String,
        unit: String,
        sourceLabel: String
    ): ChartSeries = ChartSeries(id, title, unit, sourceLabel, emptyList())

    private fun weekStartMs(timeMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone, Locale.US)
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.timeInMillis = timeMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val offset = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.SUNDAY -> -6
            else -> Calendar.MONDAY - dayOfWeek
        }
        cal.add(Calendar.DAY_OF_MONTH, offset)
        return cal.timeInMillis
    }

    private fun weekLabel(weekStartMs: Long, timeZone: TimeZone): String {
        val cal = Calendar.getInstance(timeZone, Locale.US)
        cal.timeInMillis = weekStartMs
        return String.format(
            Locale.US,
            "%04d-W%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.WEEK_OF_YEAR)
        )
    }

    private fun round1(value: Double): Double = (value * 10.0).toLong() / 10.0
    private fun round2(value: Double): Double = (value * 100.0).toLong() / 100.0
}
