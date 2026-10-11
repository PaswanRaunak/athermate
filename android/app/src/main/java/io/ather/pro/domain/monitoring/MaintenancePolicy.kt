package io.ather.pro.domain.monitoring

import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoField
import kotlin.math.ceil

/**
 * Odometer- and calendar-based service reminders. Pure state transitions; the
 * caller persists the returned latch so a reminder fires once per boundary and
 * survives restarts.
 *
 * First observation initialises the schedule without notifying, so a fresh
 * install never replays years of missed services; the first reminder is the
 * next boundary after the odometer is first seen (or after "mark serviced").
 */
object MaintenancePolicy {

    const val SERVICE_INTERVAL_KM = 3_000.0
    /** Warn this far ahead of the service boundary, not only once it is crossed. */
    const val SERVICE_EARLY_WARNING_KM = 200.0

    data class ServiceLatch(
        val nextDueOdoKm: Double? = null,
        val lastNotifiedDueOdoKm: Double? = null
    )

    data class ServiceDecision(
        val latch: ServiceLatch,
        val fireNotification: Boolean,
        val nextDueOdoKm: Double?,
        val kmRemaining: Double?
    ) {
        val dueNow: Boolean get() = kmRemaining != null && kmRemaining <= 0.0
        val dueSoon: Boolean get() = kmRemaining != null && !dueNow &&
            kmRemaining <= SERVICE_EARLY_WARNING_KM
    }

    data class TyreLatch(
        val lastNotifiedEpochMonth: Long? = null,
        val lastCheckedEpochMonth: Long? = null
    )

    data class TyreDecision(
        val latch: TyreLatch,
        val fireNotification: Boolean,
        val lastCheckedEpochMonth: Long?
    )

    fun evaluateService(
        latch: ServiceLatch,
        odometerKm: Double?,
        intervalKm: Double = SERVICE_INTERVAL_KM
    ): ServiceDecision {
        if (odometerKm == null || !odometerKm.isFinite() || odometerKm < 0.0 || intervalKm <= 0.0) {
            return ServiceDecision(latch, false, latch.nextDueOdoKm, null)
        }
        if (latch.nextDueOdoKm == null) {
            // Initialisation only: schedule the next boundary, never nag on install.
            val nextDue = nextBoundary(odometerKm, intervalKm)
            return ServiceDecision(latch.copy(nextDueOdoKm = nextDue), false, nextDue,
                nextDue - odometerKm)
        }
        val fire = odometerKm >= latch.nextDueOdoKm &&
            latch.lastNotifiedDueOdoKm != latch.nextDueOdoKm
        // The due boundary stays put until the rider acknowledges the service:
        // a missed service keeps showing as overdue instead of quietly rescheduling.
        val notified = if (fire) latch.nextDueOdoKm else latch.lastNotifiedDueOdoKm
        return ServiceDecision(
            latch = latch.copy(lastNotifiedDueOdoKm = notified),
            fireNotification = fire,
            nextDueOdoKm = latch.nextDueOdoKm,
            kmRemaining = latch.nextDueOdoKm - odometerKm
        )
    }

    /** User tapped "mark serviced": next due is a full interval from the serviced odometer. */
    fun markServiced(latch: ServiceLatch, odometerKm: Double?, intervalKm: Double = SERVICE_INTERVAL_KM): ServiceLatch {
        val base = odometerKm?.takeIf { it.isFinite() && it >= 0.0 } ?: return latch
        val nextDue = base + intervalKm
        return ServiceLatch(nextDueOdoKm = nextDue, lastNotifiedDueOdoKm = null)
    }

    fun evaluateTyreCheck(
        latch: TyreLatch,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): TyreDecision {
        val month = YearMonth.from(java.time.Instant.ofEpochMilli(nowMs).atZone(zone)).getLong(ChronoField.PROLEPTIC_MONTH)
        if (latch.lastNotifiedEpochMonth == null) {
            return TyreDecision(latch.copy(lastNotifiedEpochMonth = month), false, latch.lastCheckedEpochMonth)
        }
        // A check marked this month already covers the reminder; do not nag anyway.
        val handled = latch.lastCheckedEpochMonth != null && latch.lastCheckedEpochMonth >= month
        val fire = month > latch.lastNotifiedEpochMonth && !handled
        return TyreDecision(
            latch = latch.copy(lastNotifiedEpochMonth = if (fire || handled) month else latch.lastNotifiedEpochMonth),
            fireNotification = fire,
            lastCheckedEpochMonth = latch.lastCheckedEpochMonth
        )
    }

    fun markTyreChecked(latch: TyreLatch, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): TyreLatch {
        val month = YearMonth.from(java.time.Instant.ofEpochMilli(nowMs).atZone(zone)).getLong(ChronoField.PROLEPTIC_MONTH)
        return latch.copy(lastCheckedEpochMonth = month, lastNotifiedEpochMonth = month)
    }

    fun epochMonth(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        YearMonth.from(java.time.Instant.ofEpochMilli(nowMs).atZone(zone)).getLong(ChronoField.PROLEPTIC_MONTH)

    private fun nextBoundary(odometerKm: Double, intervalKm: Double): Double {
        val boundary = ceil(odometerKm / intervalKm) * intervalKm
        // Sitting exactly on a boundary belongs to the next interval.
        return if (boundary == odometerKm) boundary + intervalKm else boundary
    }
}
