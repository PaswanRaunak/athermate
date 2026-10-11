package io.ather.pro.domain.monitoring

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class MaintenancePolicyTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    // ---------- Service schedule ----------

    @Test fun firstOdometerSchedulesWithoutNotifying() {
        val decision = MaintenancePolicy.evaluateService(MaintenancePolicy.ServiceLatch(), 9_500.0)
        assertFalse(decision.fireNotification)
        assertEquals(12_000.0, decision.latch.nextDueOdoKm!!, 0.001)
        assertEquals(2_500.0, decision.kmRemaining!!, 0.001)
        assertFalse(decision.dueNow)
    }

    @Test fun odometerExactlyOnBoundaryBelongsToNextInterval() {
        val decision = MaintenancePolicy.evaluateService(MaintenancePolicy.ServiceLatch(), 9_000.0)
        assertEquals(12_000.0, decision.latch.nextDueOdoKm!!, 0.001)
    }

    @Test fun crossingTheBoundaryFiresOnce() {
        var latch = MaintenancePolicy.evaluateService(MaintenancePolicy.ServiceLatch(), 8_000.0).latch
        val first = MaintenancePolicy.evaluateService(latch, 9_050.0)
        assertTrue(first.fireNotification)
        assertTrue(first.dueNow)
        assertEquals(-50.0, first.kmRemaining!!, 0.001)
        assertEquals(9_000.0, first.latch.nextDueOdoKm!!, 0.001)
        // Same boundary again: no repeat notification, and the overdue state persists…
        val again = MaintenancePolicy.evaluateService(first.latch, 9_100.0)
        assertFalse(again.fireNotification)
        assertTrue(again.dueNow)
        // …until the rider acknowledges the service.
        val after = MaintenancePolicy.evaluateService(
            MaintenancePolicy.markServiced(first.latch, 9_100.0), 9_100.0)
        assertFalse(after.dueNow)
        assertEquals(3_000.0, after.kmRemaining!!, 0.001)
    }

    @Test fun earlyWarningWindowFlagsDueSoon() {
        val latch = MaintenancePolicy.evaluateService(MaintenancePolicy.ServiceLatch(), 8_000.0).latch
        val decision = MaintenancePolicy.evaluateService(latch, 8_850.0)
        assertFalse(decision.fireNotification)
        assertTrue(decision.dueSoon)
        assertFalse(decision.dueNow)
    }

    @Test fun multiIntervalJumpProducesOneReminderAndStaysOverdue() {
        var latch = MaintenancePolicy.evaluateService(MaintenancePolicy.ServiceLatch(), 1_000.0).latch
        val decision = MaintenancePolicy.evaluateService(latch, 7_500.0)
        assertTrue(decision.fireNotification)
        assertTrue(decision.dueNow)
        assertEquals(-4_500.0, decision.kmRemaining!!, 0.001)
        // Still the same boundary: one reminder, no per-skipped-service nagging.
        assertEquals(3_000.0, decision.latch.nextDueOdoKm!!, 0.001)
    }

    @Test fun markServicedReschedulesAFullIntervalFromNow() {
        val latch = MaintenancePolicy.markServiced(MaintenancePolicy.ServiceLatch(9_000.0, 9_000.0), 9_100.0)
        assertEquals(12_100.0, latch.nextDueOdoKm!!, 0.001)
        assertNull(latch.lastNotifiedDueOdoKm)
        val decision = MaintenancePolicy.evaluateService(latch, 9_100.0)
        assertFalse(decision.fireNotification)
        assertEquals(3_000.0, decision.kmRemaining!!, 0.001)
    }

    @Test fun missingOrInvalidOdometerKeepsScheduleIntact() {
        val latch = MaintenancePolicy.ServiceLatch(9_000.0, null)
        for (bad in listOf<Double?>(null, -5.0, Double.NaN)) {
            val decision = MaintenancePolicy.evaluateService(latch, bad)
            assertFalse(decision.fireNotification)
            assertEquals(latch, decision.latch)
        }
    }

    // ---------- Tyre check ----------

    private fun at(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun firstObservationInitialisesTyreLatchQuietly() {
        val decision = MaintenancePolicy.evaluateTyreCheck(MaintenancePolicy.TyreLatch(), at(2026, 9, 15), zone)
        assertFalse(decision.fireNotification)
        assertEquals(YearMonth.of(2026, 9).getLong(java.time.temporal.ChronoField.PROLEPTIC_MONTH), decision.latch.lastNotifiedEpochMonth)
    }

    @Test fun newCalendarMonthFiresOnce() {
        var latch = MaintenancePolicy.evaluateTyreCheck(MaintenancePolicy.TyreLatch(), at(2026, 9, 15), zone).latch
        val october = MaintenancePolicy.evaluateTyreCheck(latch, at(2026, 10, 2), zone)
        assertTrue(october.fireNotification)
        val later = MaintenancePolicy.evaluateTyreCheck(october.latch, at(2026, 10, 20), zone)
        assertFalse(later.fireNotification)
        assertEquals(YearMonth.of(2026, 11).getLong(java.time.temporal.ChronoField.PROLEPTIC_MONTH),
            MaintenancePolicy.evaluateTyreCheck(later.latch, at(2026, 11, 1), zone)
                .also { assertTrue(it.fireNotification) }.latch.lastNotifiedEpochMonth)
    }

    @Test fun checkMarkedThisMonthSuppressesTheReminder() {
        var latch = MaintenancePolicy.evaluateTyreCheck(MaintenancePolicy.TyreLatch(), at(2026, 9, 15), zone).latch
        latch = MaintenancePolicy.markTyreChecked(latch, at(2026, 10, 1), zone)
        val decision = MaintenancePolicy.evaluateTyreCheck(latch, at(2026, 10, 5), zone)
        assertFalse(decision.fireNotification)
        // And November still reminds.
        assertTrue(MaintenancePolicy.evaluateTyreCheck(decision.latch, at(2026, 11, 5), zone).fireNotification)
    }

    @Test fun sameMonthAcrossTimeZonesUsesLocalCalendar() {
        val utcLateNight = ZonedDateTime.of(2026, 9, 30, 23, 30, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val ist = ZoneId.of("Asia/Kolkata") // still 1 Oct in IST: a different month
        var latch = MaintenancePolicy.TyreLatch(lastNotifiedEpochMonth =
            YearMonth.of(2026, 9).getLong(java.time.temporal.ChronoField.PROLEPTIC_MONTH))
        assertTrue(MaintenancePolicy.evaluateTyreCheck(latch, utcLateNight, ist).fireNotification)
        latch = MaintenancePolicy.TyreLatch(lastNotifiedEpochMonth = YearMonth.of(2026, 9).getLong(java.time.temporal.ChronoField.PROLEPTIC_MONTH))
        assertFalse(MaintenancePolicy.evaluateTyreCheck(latch, utcLateNight, ZoneId.of("UTC")).fireNotification)
    }
}
