package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ChargingSessionRecord
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class ChargingSessionHistoryTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun session(start: Long, durationMin: Long, energy: Double?, cost: Double?) =
        ChargingSessionRecord(
            startedAtMs = start, endedAtMs = start + durationMin * 60_000,
            startSoc = 20.0, endSoc = 80.0, energyKWh = energy, costInr = cost)

    @Test fun sessionsGroupIntoMonthlyBucketsNewestFirst() {
        val sessions = listOf(
            session(at(2026, 10, 5, 21), 90, 2.0, 16.0),
            session(at(2026, 9, 28, 8), 60, 1.5, 12.0),
            session(at(2026, 10, 7, 6), 45, 1.0, 8.0)
        )
        val buckets = ChargingSessionHistory.monthlyBuckets(sessions, zone)
        assertEquals(2, buckets.size)
        assertEquals(YearMonth.of(2026, 10), buckets[0].month)
        assertEquals(YearMonth.of(2026, 9), buckets[1].month)
        assertEquals(2, buckets[0].sessionCount)
        assertEquals(3.0, buckets[0].energyKWh, 0.001)
        assertEquals(24.0, buckets[0].costInr, 0.001)
        assertEquals(135, buckets[0].chargeMinutes)
        assertEquals(1, buckets[1].sessionCount)
        assertEquals(12.0, buckets[1].costInr, 0.001)
    }

    @Test fun unmeasuredSessionsCountTowardTotalButNotEnergy() {
        val sessions = listOf(
            session(at(2026, 10, 2, 9), 30, 1.2, 9.6),
            session(at(2026, 10, 3, 9), 40, null, null)
        )
        val bucket = ChargingSessionHistory.monthlyBuckets(sessions, zone).single()
        assertEquals(2, bucket.sessionCount)
        assertEquals(1.2, bucket.energyKWh, 0.001)
        assertEquals(9.6, bucket.costInr, 0.001)
        assertTrue(bucket.hasUnknownEnergy)
    }

    @Test fun fullyMeasuredMonthDoesNotFlagUnknownEnergy() {
        val bucket = ChargingSessionHistory.monthlyBuckets(
            listOf(session(at(2026, 10, 2, 9), 30, 1.2, 9.6)), zone).single()
        assertFalse(bucket.hasUnknownEnergy)
    }

    @Test fun malformedTimestampsAreIgnored() {
        val sessions = listOf(
            session(at(2026, 10, 2, 9), 30, 1.2, 9.6),
            session(0, 30, 1.0, 8.0),
            session(at(2026, 10, 4, 9) + 60_000, -60_000 * 5, 1.0, 8.0)
        )
        val bucket = ChargingSessionHistory.monthlyBuckets(sessions, zone).single()
        assertEquals(1, bucket.sessionCount)
    }

    @Test fun bucketForReturnsMatchingMonthOrNull() {
        val sessions = listOf(session(at(2026, 10, 5, 21), 90, 2.0, 16.0))
        val history = ChargingSessionHistory
        assertEquals(16.0, history.bucketFor(sessions, YearMonth.of(2026, 10), zone)!!.costInr, 0.001)
        assertNull(history.bucketFor(sessions, YearMonth.of(2026, 9), zone))
    }

    @Test fun emptyHistoryYieldsNoBuckets() {
        assertTrue(ChargingSessionHistory.monthlyBuckets(emptyList(), zone).isEmpty())
    }
}
