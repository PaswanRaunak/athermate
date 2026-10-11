package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ChargingSessionRecord
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Aggregates completed charging sessions into calendar-month buckets with energy and cost totals. */
object ChargingSessionHistory {

    data class MonthBucket(
        val month: YearMonth,
        val sessionCount: Int,
        val energyKWh: Double,
        val costInr: Double,
        val chargeMinutes: Long,
        /** At least one session lacks a SoC-derived energy reading, so the totals understate. */
        val hasUnknownEnergy: Boolean
    )

    /**
     * Buckets newest-first. Cost uses the tariff recorded when each session ended,
     * so later tariff edits never rewrite past spending.
     */
    fun monthlyBuckets(
        sessions: List<ChargingSessionRecord>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<MonthBucket> = sessions
        .filter { it.startedAtMs > 0 && it.endedAtMs >= it.startedAtMs }
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAtMs).atZone(zone)) }
        .map { (month, list) ->
            MonthBucket(
                month = month,
                sessionCount = list.size,
                energyKWh = list.sumOf { it.energyKWh ?: 0.0 }.rounded(),
                costInr = list.sumOf { it.costInr ?: 0.0 }.rounded(),
                chargeMinutes = list.sumOf { (it.endedAtMs - it.startedAtMs) / 60_000 },
                hasUnknownEnergy = list.any { it.energyKWh == null }
            )
        }
        .sortedByDescending { it.month }

    fun bucketFor(
        sessions: List<ChargingSessionRecord>,
        target: YearMonth,
        zone: ZoneId = ZoneId.systemDefault()
    ): MonthBucket? = monthlyBuckets(sessions, zone).firstOrNull { it.month == target }

    private fun Double.rounded() = (this * 100).toInt() / 100.0
}
