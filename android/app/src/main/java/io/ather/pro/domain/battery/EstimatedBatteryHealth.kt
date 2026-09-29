package io.ather.pro.domain.battery

import io.ather.pro.domain.model.TripRecord
import kotlin.math.sqrt

/**
 * Battery health display policy:
 * - Prefer verified BMS SoH when the API returns it.
 * - Otherwise estimate only from an independent official Wh/km reference plus
 *   local odometer distance and measured SoC drop.
 * - Never invent values or treat the vehicle-health scorecard as BMS SoH.
 */
object EstimatedBatteryHealth {
    const val MIN_SAMPLES = 5
    const val MIN_SOC_DROP_PERCENT = 5.0
    const val MIN_REFERENCE_TRIPS = 3
    const val MIN_REFERENCE_DISTANCE_KM = 20.0

    sealed class Display {
        data class ReportedBms(val percent: Double) : Display()

        data class Estimated(
            val percent: Double,
            val sampleCount: Int,
            val confidenceLabel: String,
            val methodLabel: String
        ) : Display()

        data object Unavailable : Display()
    }

    fun resolve(
        reportedBmsSohPercent: Double?,
        trips: List<TripRecord>,
        nominalCapacityWh: Double
    ): Display {
        val reported = reportedBmsSohPercent?.takeIf { it.isFinite() && it > 0.0 }
        if (reported != null) {
            return Display.ReportedBms(reported)
        }
        return estimateFromTrips(trips, nominalCapacityWh)
    }

    fun estimateFromTrips(
        trips: List<TripRecord>,
        nominalCapacityWh: Double
    ): Display {
        if (!nominalCapacityWh.isFinite() || nominalCapacityWh <= 0.0) {
            return Display.Unavailable
        }
        // The official rides API supplies distance and Wh/km but not measured SoC
        // drop. Use those rides only to establish an independent consumption
        // reference; never use their nominal-capacity-derived socConsumed value.
        val referenceTrips = trips.filter { trip ->
            trip.isOfficialRide &&
                trip.distanceKm > 0.0 &&
                trip.efficiencyWhPerKm in 8.0..120.0 &&
                trip.energyConsumedWh > 0.0
        }
        val referenceDistanceKm = referenceTrips.sumOf(TripRecord::distanceKm)
        if (referenceTrips.size < MIN_REFERENCE_TRIPS ||
            referenceDistanceKm < MIN_REFERENCE_DISTANCE_KM
        ) {
            return Display.Unavailable
        }
        val referenceWhPerKm = referenceTrips.sumOf(TripRecord::energyConsumedWh) /
            referenceDistanceKm
        if (!referenceWhPerKm.isFinite() || referenceWhPerKm !in 8.0..120.0) {
            return Display.Unavailable
        }

        val samples = trips.mapNotNull { trip ->
            // Local auto trips provide odometer distance + observed SoC drop. Their
            // energy field may be derived from nominal capacity, so intentionally do
            // not feed it into the health calculation (that would force ~100% SoH).
            if (trip.isOfficialRide || trip.distanceKm < 3.0) return@mapNotNull null
            val socDrop = trip.socConsumed
            if (socDrop < MIN_SOC_DROP_PERCENT) return@mapNotNull null
            val impliedCapacityWh = (trip.distanceKm * referenceWhPerKm) / (socDrop / 100.0)
            impliedCapacityWh.takeIf { capacity ->
                capacity.isFinite() &&
                    capacity in (nominalCapacityWh * 0.50)..(nominalCapacityWh * 1.20)
            }
        }
        if (samples.size < MIN_SAMPLES) {
            return Display.Unavailable
        }

        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val mean = samples.average()
        val variance = samples.map { val d = it - mean; d * d }.average()
        val cv = sqrt(variance) / mean
        val soh = (median / nominalCapacityWh) * 100.0
        if (!soh.isFinite() || soh < 40.0 || soh > 120.0) {
            // Outside a physically plausible band — refuse to show a number.
            return Display.Unavailable
        }

        val confidence = when {
            samples.size >= 12 && cv < 0.15 -> "Higher"
            samples.size >= 8 && cv < 0.25 -> "Medium"
            else -> "Lower"
        }
        return Display.Estimated(
            percent = soh,
            sampleCount = samples.size,
            confidenceLabel = "$confidence confidence",
            methodLabel = "Estimate: Cloud ride efficiency × local odometer distance ÷ measured SoC drop; ${referenceTrips.size} reference rides, ${samples.size} local samples"
        )
    }
}

/**
 * km per electricity unit from observed ride distance and energy only.
 * One unit = 1 kWh. Formula: km/unit = distanceKm / (energyWh / 1000).
 * Never derived from Wh/km labels or invented constants.
 */
fun TripRecord.kmPerUnitOrNull(): Double? {
    if (distanceKm <= 0.0 || energyConsumedWh <= 0.0) return null
    return distanceKm / (energyConsumedWh / 1000.0)
}

/** @deprecated Use [kmPerUnitOrNull]; unit means kWh. */
fun TripRecord.kmPerKwhOrNull(): Double? = kmPerUnitOrNull()

fun rideEfficiencyKmPerUnit(trips: List<TripRecord>): Double? {
    val distance = trips.sumOf { it.distanceKm }
    val energyWh = trips.sumOf { it.energyConsumedWh }
    if (distance <= 0.0 || energyWh <= 0.0) return null
    return distance / (energyWh / 1000.0)
}

/** @deprecated Use [rideEfficiencyKmPerUnit]. */
fun rideEfficiencyKmPerKwh(trips: List<TripRecord>): Double? = rideEfficiencyKmPerUnit(trips)
