package io.ather.pro.domain.charging

import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterTelemetry

/**
 * Pure orchestration for pause/resume dispatch + telemetry-first confirmation.
 * Exactly one gateway call per successful [attempt]; never invents scooter state.
 */
class RemoteChargingDispatcher(
    private val gateway: RemoteChargingGateway,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val confirmTimeoutMs: Long = ChargingControl.CONFIRM_TIMEOUT_MS
) {
    data class Attempt(
        /** Updated command to persist before/without network. */
        val command: RemoteChargingCommand,
        /** True only when exactly one shadow mutation was enqueued. */
        val dispatched: Boolean,
        val action: String? = null,
        val scooterUuid: String? = null
    )

    /**
     * @param start true → action=start (Resume); false → action=stop (Pause)
     * @param supersedePending when true, an explicit rider tap replaces any pending
     *   request, including a same-action request whose callback/telemetry stalled.
     */
    fun attempt(
        start: Boolean,
        token: String?,
        scooterUuid: String?,
        telemetry: ScooterTelemetry?,
        current: RemoteChargingCommand,
        supersedePending: Boolean = true,
        onHttpResult: (Result<Unit>) -> Unit
    ): Attempt {
        val action = if (start) "start" else "stop"
        val now = nowMs()

        if (token.isNullOrBlank() || scooterUuid.isNullOrBlank()) {
            return Attempt(
                command = current.copy(
                    action = action,
                    phase = RemoteCommandPhase.ERROR,
                    message = "Missing JWT or scooter UUID — cannot dispatch $action.",
                    requestedAt = now
                ),
                dispatched = false
            )
        }
        // First expire a genuinely stale latch. Do not require telemetry to accept a
        // deliberate manual command: WebSocket state can lag even though the HTTP
        // shadow endpoint is available.
        var advanced = ChargingControl.advanceCommand(current, telemetry, now, confirmTimeoutMs)
        val pending = advanced.phase == RemoteCommandPhase.SENDING ||
            advanced.phase == RemoteCommandPhase.ACCEPTED
        if (pending) {
            val sameAction = advanced.action.equals(action, ignoreCase = true)
            if (!supersedePending) {
                return Attempt(
                    command = advanced.copy(
                        message = if (sameAction) {
                            "${if (start) "Start/Resume" else "Stop/Pause"} already sent; waiting for scooter confirmation."
                        } else {
                            advanced.message ?: "Another charging request is still pending."
                        }
                    ),
                    dispatched = false
                )
            }
            // A deliberate rider tap must always issue a fresh request. The repository
            // also matches requestedAt so a late callback cannot overwrite this one.
            advanced = ChargingControl.idleCommand()
        }
        val sending = RemoteChargingCommand(
            action = action,
            phase = RemoteCommandPhase.SENDING,
            // Ather briefly toggles chargerConnected during a valid remote command.
            // That intermediate frame is not a command failure and must not be shown
            // as an alarming unplugged/disconnected warning.
            message = if (start) {
                "Sending start/resume request…"
            } else {
                "Sending stop/pause request…"
            },
            requestedAt = now
        )

        gateway.setRemoteCharging(token, scooterUuid, start) { result ->
            onHttpResult(result)
        }

        return Attempt(
            command = sending,
            dispatched = true,
            action = action,
            scooterUuid = scooterUuid
        )
    }

    fun applyHttpResult(
        current: RemoteChargingCommand,
        expectedAction: String,
        result: Result<Unit>,
        expectedRequestedAt: Long? = null
    ): RemoteChargingCommand {
        if (current.action != expectedAction) return current
        if (expectedRequestedAt != null && current.requestedAt != expectedRequestedAt) return current
        if (current.phase != RemoteCommandPhase.SENDING &&
            current.phase != RemoteCommandPhase.ACCEPTED
        ) {
            return current
        }
        return result.fold(
            onSuccess = {
                current.copy(
                    phase = RemoteCommandPhase.ACCEPTED,
                    message = "Ather accepted the request; waiting for scooter confirmation."
                )
            },
            onFailure = { error ->
                current.copy(
                    phase = RemoteCommandPhase.ERROR,
                    message = error.message ?: "Ather rejected the charging request."
                )
            }
        )
    }

    fun clearLatch(): RemoteChargingCommand = ChargingControl.idleCommand()
}
