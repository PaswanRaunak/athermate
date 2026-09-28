package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry

/**
 * Phone-app charge-limit automation (not a firmware SoC cap).
 *
 * Issues at most one remote stop per armed session when fresh live
 * plugged-and-charging telemetry reaches the selected percentage.
 * Re-arms only after unplug (later charging session) or a deliberate
 * settings change. Never acts on stale or unplugged frames.
 */
object ChargeLimitController {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 100
    // Safe initial slider position only. The limit starts DISABLED and no stop is
    // issued until the rider explicitly chooses/enables a percentage.
    const val DEFAULT_PERCENT = MAX_PERCENT
    const val CONFIRM_TIMEOUT_MS: Long = 45_000L
    const val FRESH_MAX_AGE_MS: Long = 30_000L

    enum class Status {
        DISABLED,
        MONITORING,
        PENDING,
        CONFIRMED,
        ERROR
    }

    data class Snapshot(
        val enabled: Boolean = false,
        val percent: Int = DEFAULT_PERCENT,
        val status: Status = Status.DISABLED,
        val message: String? = null,
        /** When true, a threshold crossing may request exactly one stop. */
        val armed: Boolean = false,
        val pendingSinceMs: Long? = null
    )

    sealed class Decision {
        data object None : Decision()
        data class StateOnly(val next: Snapshot) : Decision()
        /** Caller must issue exactly one existing remote stop request. */
        data class RequestStop(val next: Snapshot) : Decision()
    }

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun applySettings(
        previous: Snapshot,
        enabled: Boolean,
        percent: Int
    ): Snapshot {
        val clamped = clampPercent(percent)
        if (!enabled) {
            return Snapshot(
                enabled = false,
                percent = clamped,
                status = Status.DISABLED,
                message = null,
                armed = false,
                pendingSinceMs = null
            )
        }
        val settingsChanged =
            !previous.enabled || previous.percent != clamped
        return Snapshot(
            enabled = true,
            percent = clamped,
            status = Status.MONITORING,
            message = if (settingsChanged) {
                "Limit set to $clamped%. Phone-app automation — not a firmware charge limit."
            } else {
                previous.message
            },
            // Deliberate enable / percent change re-arms for this session.
            armed = true,
            pendingSinceMs = null
        )
    }

    fun retry(previous: Snapshot): Snapshot {
        if (!previous.enabled) return previous
        if (previous.status != Status.ERROR && previous.status != Status.PENDING) {
            return previous
        }
        return previous.copy(
            status = Status.MONITORING,
            message = "Retry armed — will stop once at ${previous.percent}% if still charging.",
            armed = true,
            pendingSinceMs = null
        )
    }

    fun onTelemetry(
        state: Snapshot,
        telemetry: ScooterTelemetry?,
        lastUpdatedMs: Long?,
        nowMs: Long,
        freshMaxAgeMs: Long = FRESH_MAX_AGE_MS,
        confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS
    ): Decision {
        if (!state.enabled) {
            return if (state.status == Status.DISABLED && !state.armed && state.message == null) {
                Decision.None
            } else {
                Decision.StateOnly(
                    Snapshot(
                        enabled = false,
                        percent = state.percent,
                        status = Status.DISABLED,
                        message = null,
                        armed = false,
                        pendingSinceMs = null
                    )
                )
            }
        }

        val plugged = ChargingControl.isPluggedIn(telemetry)
        val active = ChargingControl.isActivelyCharging(telemetry)
        val fresh = isFresh(lastUpdatedMs, nowMs, freshMaxAgeMs)
        val soc = telemetry?.batterySoc

        // Pending confirmation / timeout first.
        if (state.status == Status.PENDING) {
            val started = state.pendingSinceMs ?: nowMs
            if (nowMs - started >= confirmTimeoutMs) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.ERROR,
                        message = "Stop request timed out. Retry when still plugged and charging.",
                        pendingSinceMs = null
                    )
                )
            }
            if (fresh && !active) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.CONFIRMED,
                        message = "Stopped at ${state.percent}% limit. Monitoring remains enabled.",
                        armed = false,
                        pendingSinceMs = null
                    )
                )
            }
            // Duplicate / still-charging frames: do not request another stop.
            return Decision.None
        }

        // If the rider resumes while still at/above the selected limit, enforce the
        // limit again. This is one request per charging restart, not per telemetry frame.
        if (state.status == Status.CONFIRMED) {
            if (fresh && active && soc != null) {
                return if (soc >= state.percent) {
                    Decision.RequestStop(
                        state.copy(
                            status = Status.PENDING,
                            message = "Charging restarted above ${state.percent}% (SoC ${soc.toInt()}%). Sending one stop…",
                            armed = false,
                            pendingSinceMs = nowMs
                        )
                    )
                } else {
                    Decision.StateOnly(
                        state.copy(
                            status = Status.MONITORING,
                            message = "Charging restarted below the limit; monitoring ${state.percent}%.",
                            armed = true,
                            pendingSinceMs = null
                        )
                    )
                }
            }
            if (!plugged) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.MONITORING,
                        message = "Ready to enforce ${state.percent}% on the next charging session.",
                        armed = true,
                        pendingSinceMs = null
                    )
                )
            }
            return Decision.None
        }

        // ERROR stays until retry / settings change; still re-arm on unplug for next session.
        if (state.status == Status.ERROR) {
            if (!plugged && !state.armed) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.MONITORING,
                        message = "Ready for the next charging session.",
                        armed = true,
                        pendingSinceMs = null
                    )
                )
            }
            return Decision.None
        }

        // MONITORING path.
        if (!plugged) {
            // Stay monitoring; keep armed for when a charge session starts.
            if (state.status != Status.MONITORING || state.message != null && !state.armed) {
                return Decision.StateOnly(
                    state.copy(
                        status = Status.MONITORING,
                        message = state.message,
                        armed = true,
                        pendingSinceMs = null
                    )
                )
            }
            return Decision.None
        }

        // Never act on stale or incomplete live frames.
        if (!fresh || soc == null || !active) {
            return if (state.status != Status.MONITORING) {
                Decision.StateOnly(
                    state.copy(status = Status.MONITORING, pendingSinceMs = null)
                )
            } else {
                Decision.None
            }
        }

        if (state.armed && soc >= state.percent) {
            return Decision.RequestStop(
                state.copy(
                    status = Status.PENDING,
                    message = "Limit ${state.percent}% reached (SoC ${soc.toInt()}%). Sending one stop…",
                    armed = false,
                    pendingSinceMs = nowMs
                )
            )
        }

        return if (state.status != Status.MONITORING) {
            Decision.StateOnly(state.copy(status = Status.MONITORING, pendingSinceMs = null))
        } else {
            Decision.None
        }
    }

    fun isFresh(lastUpdatedMs: Long?, nowMs: Long, maxAgeMs: Long = FRESH_MAX_AGE_MS): Boolean {
        if (lastUpdatedMs == null) return false
        val age = nowMs - lastUpdatedMs
        return age in 0 until maxAgeMs
    }
}
