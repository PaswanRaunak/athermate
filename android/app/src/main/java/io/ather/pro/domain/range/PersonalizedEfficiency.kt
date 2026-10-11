package io.ather.pro.domain.range

import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord

/**
 * Learns how much energy this rider actually consumes from completed trips and
 * adjusts Ather's generic range estimates toward that figure.
 *
 * The learned value is a distance-weighted mean over a rolling window, so one
 * eco weekend cannot dominate a month of commuting. Confidence ramps with the
 * observed distance: the blend starts at Ather's estimate and moves toward the
 * personal figure, never fully replacing it while cloud data is available.
 */
object PersonalizedEfficiency {

    const val WINDOW_DAYS = 30
    /** Trips shorter than this carry more noise than signal. */
    const val MIN_TRIP_KM = 0.5
    const val MIN_WH_PER_KM = 8.0
    const val MAX_WH_PER_KM = 120.0
    /** Observed distance at which the personal figure reaches [MAX_WEIGHT]. */
    const val CONFIDENCE_KM = 300.0
    const val MAX_WEIGHT = 0.7
    /** Without a reported range to blend with, demand this much history first. */
    const val MIN_OBSERVED_KM_NO_REPORT = 10.0

    data class LearnedEfficiency(
        val whPerKm: Double,
        val observedKm: Double,
        val sampleCount: Int,
        val windowDays: Int
    )

    fun fromTrips(
        trips: List<TripRecord>,
        nowMs: Long,
        windowDays: Int = WINDOW_DAYS
    ): LearnedEfficiency? {
        val windowStartMs = nowMs - windowDays * 24L * 60 * 60_000
        var weightedWhPerKm = 0.0
        var observedKm = 0.0
        var sampleCount = 0
        trips.forEach { trip ->
            val efficiency = trip.efficiencyWhPerKm.takeIf {
                it.isFinite() && it in MIN_WH_PER_KM..MAX_WH_PER_KM
            } ?: return@forEach
            val distance = trip.distanceKm.takeIf { it.isFinite() && it >= MIN_TRIP_KM }
                ?: return@forEach
            if (trip.startTimeMs !in windowStartMs..nowMs) return@forEach
            weightedWhPerKm += efficiency * distance
            observedKm += distance
            sampleCount += 1
        }
        if (observedKm <= 0.0 || sampleCount == 0) return null
        return LearnedEfficiency(
            whPerKm = weightedWhPerKm / observedKm,
            observedKm = observedKm,
            sampleCount = sampleCount,
            windowDays = windowDays
        )
    }

    /**
     * Personalized current range: remaining usable energy at the learned Wh/km,
     * blended toward the vehicle-reported figure by observed confidence.
     */
    fun currentRangeKm(
        telemetry: ScooterTelemetry?,
        learned: LearnedEfficiency?,
        model: ScooterModel?
    ): Double? {
        if (learned == null || model == null) return null
        if (!learned.whPerKm.isFinite() || learned.whPerKm <= 0.0) return null
        val soc = telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val weight = confidenceWeight(learned.observedKm)
        if (weight <= 0.0) return null
        val remainingWh = model.usableCapacityWh * (soc / 100.0)
        val learnedKm = remainingWh / learned.whPerKm
        if (!learnedKm.isFinite() || learnedKm < 0.0) return null
        val reported = telemetry?.rangeKm?.takeIf { it.isFinite() && it > 0.0 }
        return when {
            reported != null -> reported + (learnedKm - reported) * weight
            learned.observedKm >= MIN_OBSERVED_KM_NO_REPORT -> learnedKm
            else -> null
        }
    }

    fun confidenceWeight(observedKm: Double): Double =
        if (!observedKm.isFinite() || observedKm <= 0.0) 0.0
        else (observedKm / CONFIDENCE_KM).coerceAtMost(MAX_WEIGHT)
}
