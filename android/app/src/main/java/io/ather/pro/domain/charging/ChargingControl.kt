package io.ather.pro.domain.charging

import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterTelemetry

/**
 * Source-backed pause/resume charging control.
 *
 * HTTP shadow acceptance only means Ather queued the desired state. Confirmation
 * requires charging telemetry (actual current flow / pause), not the HTTP 200.
 * Stale merged charging fields must not hide Resume after a successful stop.
 */
object ChargingControl {
    const val CONFIRM_TIMEOUT_MS: Long = 45_000L

    data class View(
        val pluggedIn: Boolean,
        val activelyCharging: Boolean,
        val sessionPaused: Boolean,
        val canPause: Boolean,
        val canResume: Boolean,
        val commandPending: Boolean,
        val statusLabel: String,
        val message: String?,
        val command: RemoteChargingCommand
    )

    fun isPluggedIn(telemetry: ScooterTelemetry?): Boolean {
        if (telemetry == null) return false
        if (telemetry.chargerConnected == false) return false
        if (telemetry.chargerConnected == true) return true
        if (isActivelyCharging(telemetry)) return true
        val status = telemetry.chargingStatus.orEmpty()
        if (status.contains("pause", ignoreCase = true)) return true
        if (status.equals("Charging", ignoreCase = true)) return true
        if (status.contains("connected", ignoreCase = true)) return true
        // Avoid matching "Completed" via a naive "charg" substring.
        if (status.contains("charging", ignoreCase = true) &&
            !status.contains("complete", ignoreCase = true)
        ) {
            return true
        }
        if (telemetry.remoteChargingAction.equals("stop", ignoreCase = true) &&
            telemetry.chargerConnected != false &&
            !status.contains("complete", ignoreCase = true)
        ) {
            // Stop while still on the cable keeps the plugged session controllable.
            return true
        }
        return false
    }

    /**
     * True only when telemetry indicates actual charging current / active status.
     * Explicit pause / stop-echo wins over a stale charging=true boolean so Resume
     * stays eligible after a confirmed stop.
     */
    fun isActivelyCharging(telemetry: ScooterTelemetry?): Boolean {
        if (telemetry == null) return false
        val status = telemetry.chargingStatus.orEmpty()
        if (status.contains("pause", ignoreCase = true)) return false
        if (telemetry.remoteChargingAction.equals("stop", ignoreCase = true) &&
            !status.equals("Charging", ignoreCase = true)
        ) {
            return false
        }
        if (telemetry.charging == true) return true
        if (status.equals("Charging", ignoreCase = true)) return true
        return false
    }

    fun isPaused(telemetry: ScooterTelemetry?): Boolean {
        if (telemetry == null) return false
        if (!isPluggedIn(telemetry)) return false
        if (isActivelyCharging(telemetry)) return false
        val status = telemetry.chargingStatus.orEmpty()
        if (status.contains("pause", ignoreCase = true)) return true
        if (telemetry.remoteChargingAction.equals("stop", ignoreCase = true)) return true
        // Plugged, not drawing current — treat as paused/idle-on-cable.
        return telemetry.chargerConnected == true
    }

    /**
     * Merge charging-related fields so a stop/pause echo clears stale
     * `charging=true` / `Charging` status left behind by sparse deltas.
     */
    fun mergeChargingFields(existing: ScooterTelemetry, delta: ScooterTelemetry): ScooterTelemetry {
        val remoteAction = delta.remoteChargingAction ?: existing.remoteChargingAction
        val status = delta.chargingStatus ?: existing.chargingStatus
        val connected = delta.chargerConnected ?: existing.chargerConnected

        val pausedStatus = status?.contains("pause", ignoreCase = true) == true
        val stopEcho = remoteAction.equals("stop", ignoreCase = true)
        val startEcho = remoteAction.equals("start", ignoreCase = true)

        val charging = when {
            delta.charging != null -> delta.charging
            delta.chargingStatus != null -> {
                when {
                    pausedStatus -> false
                    delta.chargingStatus.equals("Charging", ignoreCase = true) -> true
                    else -> false
                }
            }
            // Stop echo without a fresh charging block: do not keep stale active charge.
            stopEcho && delta.remoteChargingAction != null -> false
            pausedStatus -> false
            // Start echo alone is not actual current — keep prior until charging telemetry arrives.
            startEcho && delta.remoteChargingAction != null -> existing.charging
            else -> existing.charging
        }

        val resolvedStatus = when {
            delta.chargingStatus != null -> delta.chargingStatus
            stopEcho && delta.remoteChargingAction != null &&
                existing.chargingStatus.equals("Charging", ignoreCase = true) &&
                charging != true -> "Paused"
            else -> status
        }

        return existing.copy(
            charging = charging,
            chargerConnected = connected,
            chargingStatus = resolvedStatus,
            timeToFullChargeMin = delta.timeToFullChargeMin ?: existing.timeToFullChargeMin,
            timeToEightyChargeMin = delta.timeToEightyChargeMin ?: existing.timeToEightyChargeMin,
            chargerType = delta.chargerType ?: existing.chargerType,
            remoteChargingAction = remoteAction
        )
    }

    /**
     * Advance local command phase from telemetry evidence and timeout.
     * HTTP ACCEPTED stays pending until charging telemetry confirms, or timeout → ERROR.
     */
    fun advanceCommand(
        command: RemoteChargingCommand,
        telemetry: ScooterTelemetry?,
        nowMs: Long,
        timeoutMs: Long = CONFIRM_TIMEOUT_MS
    ): RemoteChargingCommand {
        if (command.phase != RemoteCommandPhase.SENDING &&
            command.phase != RemoteCommandPhase.ACCEPTED
        ) {
            return command
        }

        val requestedAt = command.requestedAt
        if (requestedAt != null && nowMs - requestedAt >= timeoutMs) {
            return command.copy(
                phase = RemoteCommandPhase.ERROR,
                message = "Timed out waiting for scooter confirmation. You can retry."
            )
        }

        // A manual HTTP request remains valid while the telemetry socket reconnects.
        // Keep waiting (and let the timeout above release the latch) instead of
        // permanently bricking controls because a frame is temporarily absent.
        if (telemetry == null) return command

        val confirmed = when (command.action) {
            // During a successful stop Ather can briefly report chargerConnected=false
            // before settling on Paused. Actual current stopping is sufficient proof;
            // do not flash a false disconnect error during that transition.
            "stop" -> !isActivelyCharging(telemetry)
            "start" -> isActivelyCharging(telemetry)
            else -> false
        }

        if (!confirmed) {
            // Charger-connected telemetry is transient during charging transitions.
            // Keep waiting for an active/stopped signal and use the timeout for a
            // genuine failure instead of surfacing a false disconnect error.
            return command
        }

        return command.copy(
            phase = RemoteCommandPhase.CONFIRMED,
            message = if (command.action == "start") {
                "Scooter confirmed charging resumed."
            } else {
                "Scooter confirmed charging stopped."
            }
        )
    }

    fun resolveView(
        telemetry: ScooterTelemetry?,
        command: RemoteChargingCommand,
        nowMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = CONFIRM_TIMEOUT_MS
    ): View {
        var working = command
        // Fresh charging current after a confirmed stop ⇒ new session; drop stale latch
        // so Stop/Pause is not stuck gray while the bike is clearly charging again.
        if (telemetry != null &&
            working.phase == RemoteCommandPhase.CONFIRMED &&
            working.action.equals("stop", ignoreCase = true) &&
            isActivelyCharging(telemetry)
        ) {
            working = idleCommand()
        }
        val advanced = advanceCommand(working, telemetry, nowMs, timeoutMs)
        val plugged = isPluggedIn(telemetry)
        val active = isActivelyCharging(telemetry)
        val paused = isPaused(telemetry)
        val pending = advanced.phase == RemoteCommandPhase.SENDING ||
            advanced.phase == RemoteCommandPhase.ACCEPTED
        // Confirmed stop + not actively charging ⇒ Resume even if flags are messy.
        val locallyConfirmedStop =
            advanced.phase == RemoteCommandPhase.CONFIRMED &&
                advanced.action.equals("stop", ignoreCase = true) &&
                !active

        val statusLabel = when {
            active -> "Charging"
            paused || locallyConfirmedStop -> "Paused"
            plugged -> telemetry?.chargingStatus?.takeIf { it.isNotBlank() } ?: "Connected"
            !telemetry?.chargingStatus.isNullOrBlank() -> telemetry!!.chargingStatus!!
            else -> "Standby"
        }

        // Button enablement follows live plug/charge. Pending only blocks until
        // timeout/clear — callers may supersede pending on explicit tap.
        return View(
            pluggedIn = plugged,
            activelyCharging = active,
            sessionPaused = paused || locallyConfirmedStop,
            canPause = plugged && active && !pending,
            canResume = plugged && (!active || locallyConfirmedStop) && !pending,
            commandPending = pending,
            statusLabel = statusLabel,
            message = advanced.message,
            command = advanced
        )
    }

    /** Reset latch so the rider can retry after error / completed command. */
    fun idleCommand(): RemoteChargingCommand = RemoteChargingCommand()
}
