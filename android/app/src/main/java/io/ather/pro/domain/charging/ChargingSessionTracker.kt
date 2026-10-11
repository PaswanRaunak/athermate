package io.ather.pro.domain.charging

import kotlin.math.roundToInt

/**
 * Turns charging-flag transitions in telemetry into completed charging sessions,
 * with energy and cost derived from the pack capacity and the user's tariff.
 *
 * The open session survives process death via the caller persisting [Open] state:
 * without it, a restart mid-charge would lose the session start.
 */
class ChargingSessionTracker(private val nowMs: () -> Long) {

    data class Open(
        val startedAtMs: Long,
        val startSoc: Double?,
        val lastSoc: Double?,
        val updatedAtMs: Long
    )

    data class Completed(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startSoc: Double?,
        val endSoc: Double?,
        val energyKWh: Double?,
        val costInr: Double?
    )

    private var open: Open? = null

    fun restore(state: Open?) {
        // A snapshot older than six hours is a stale leftover, not a live session.
        open = state?.takeIf { nowMs() - it.updatedAtMs < STALE_MS }
    }

    fun current(): Open? = open

    /**
     * Feed one telemetry observation. Returns a [Completed] session when the
     * charging flag just fell and the session was substantial enough to keep.
     */
    fun observe(
        charging: Boolean,
        soc: Double?,
        capacityWh: Double,
        tariffPerKWh: Double
    ): Completed? {
        val now = nowMs()
        val validSoc = soc?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val current = open
        if (charging) {
            open = when {
                current == null -> Open(now, validSoc, validSoc, now)
                current.startSoc == null && validSoc != null ->
                    current.copy(startSoc = validSoc, lastSoc = validSoc, updatedAtMs = now)
                validSoc != null && (current.lastSoc == null || validSoc > current.lastSoc!!) ->
                    current.copy(lastSoc = validSoc, updatedAtMs = now)
                else -> current.copy(updatedAtMs = now)
            }
            return null
        }
        if (current == null) return null
        open = null
        // The flag-fall packet usually carries the final SoC (status flips to "Completed");
        // fold it in so the session end is not stuck at the last mid-charge reading.
        val endSoc = listOfNotNull(current.lastSoc, validSoc).maxOrNull()
        val delta = ((endSoc ?: current.startSoc ?: 0.0) -
            (current.startSoc ?: endSoc ?: 0.0)).coerceAtLeast(0.0)
        val durationMinutes = (now - current.startedAtMs) / 60_000.0
        // Phantom guards: brief flags or flat sessions are not real charging.
        if (durationMinutes < MIN_DURATION_MIN || delta < MIN_DELTA_PERCENT) return null
        val energyKWh = if (current.startSoc != null && endSoc != null) {
            capacityWh * delta / 100.0 / 1000.0
        } else null
        return Completed(
            startedAtMs = current.startedAtMs,
            endedAtMs = now,
            startSoc = current.startSoc,
            endSoc = endSoc,
            energyKWh = energyKWh?.roundTo2(),
            costInr = energyKWh?.times(tariffPerKWh)?.roundTo2()
        )
    }

    private fun Double.roundTo2() = (this * 100).roundToInt() / 100.0

    companion object {
        const val MIN_DURATION_MIN = 5.0
        const val MIN_DELTA_PERCENT = 1.0
        const val STALE_MS = 6 * 60 * 60 * 1000L
    }
}
