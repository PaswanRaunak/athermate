package io.ather.pro.domain.charging

import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic pause/resume state-machine tests. No live scooter commands.
 */
class ChargingControlTest {

    private val t0 = 1_000_000L

    @Test
    fun stopPausedStartCharging_happyPath() {
        val charging = ScooterTelemetry(
            charging = true,
            chargerConnected = true,
            chargingStatus = "Charging",
            remoteChargingAction = "start"
        )
        val idleCmd = RemoteChargingCommand()
        val chargingView = ChargingControl.resolveView(charging, idleCmd, t0)
        assertTrue(chargingView.canPause)
        assertFalse(chargingView.canResume)

        val stopSending = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.SENDING,
            message = "Sending pause request…",
            requestedAt = t0
        )
        // HTTP ACCEPTED must not flip UI to paused yet.
        val accepted = stopSending.copy(
            phase = RemoteCommandPhase.ACCEPTED,
            message = "Ather accepted the request; waiting for scooter confirmation."
        )
        val stillCharging = ChargingControl.resolveView(charging, accepted, t0 + 1_000L)
        assertTrue(stillCharging.commandPending)
        assertFalse(stillCharging.canPause)
        assertFalse(stillCharging.canResume)
        assertEquals(RemoteCommandPhase.ACCEPTED, stillCharging.command.phase)

        // Telemetry confirms pause (actual current stopped, still plugged).
        val pausedTelem = ScooterTelemetry(
            charging = false,
            chargerConnected = true,
            chargingStatus = "Paused",
            remoteChargingAction = "stop"
        )
        val confirmedStop = ChargingControl.advanceCommand(accepted, pausedTelem, t0 + 2_000L)
        assertEquals(RemoteCommandPhase.CONFIRMED, confirmedStop.phase)

        val pausedView = ChargingControl.resolveView(pausedTelem, confirmedStop, t0 + 2_000L)
        assertTrue(pausedView.canResume)
        assertFalse(pausedView.canPause)
        assertEquals("Paused", pausedView.statusLabel)

        // Resume: HTTP accept still pending until charging telemetry.
        val startAccepted = RemoteChargingCommand(
            action = "start",
            phase = RemoteCommandPhase.ACCEPTED,
            message = "Ather accepted the request; waiting for scooter confirmation.",
            requestedAt = t0 + 3_000L
        )
        val pendingStart = ChargingControl.resolveView(pausedTelem, startAccepted, t0 + 3_500L)
        assertTrue(pendingStart.commandPending)
        assertFalse(pendingStart.canResume)

        val resumedTelem = pausedTelem.copy(
            charging = true,
            chargingStatus = "Charging",
            remoteChargingAction = "start"
        )
        val confirmedStart = ChargingControl.advanceCommand(startAccepted, resumedTelem, t0 + 4_000L)
        assertEquals(RemoteCommandPhase.CONFIRMED, confirmedStart.phase)
        val resumedView = ChargingControl.resolveView(resumedTelem, confirmedStart, t0 + 4_000L)
        assertTrue(resumedView.canPause)
        assertFalse(resumedView.canResume)
        assertEquals("Charging", resumedView.statusLabel)
    }

    @Test
    fun freshPausedStatusClearsStaleChargingAndAllowsResume() {
        // Field failure: stop succeeds, but sparse deltas leave charging=true merged in.
        val before = ScooterTelemetry(
            charging = true,
            chargerConnected = true,
            chargingStatus = "Charging",
            remoteChargingAction = "start",
            batterySoc = 55.0
        )
        val stopDelta = ScooterTelemetry(
            remoteChargingAction = "stop", chargingStatus = "Paused"
        )
        val merged = before.mergeWith(stopDelta)

        assertEquals(false, merged.charging)
        assertEquals("stop", merged.remoteChargingAction)
        assertEquals("Paused", merged.chargingStatus)
        assertEquals(true, merged.chargerConnected)
        assertEquals(55.0, merged.batterySoc!!, 0.01)

        val confirmed = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.CONFIRMED,
            message = "Scooter confirmed charging paused.",
            requestedAt = t0
        )
        val view = ChargingControl.resolveView(merged, confirmed, t0 + 5_000L)
        assertTrue("Resume must stay eligible after stop despite prior stale charge flag", view.canResume)
        assertTrue(view.pluggedIn)
        assertFalse(view.activelyCharging)
        assertEquals("Paused", view.statusLabel)
    }

    @Test
    fun httpRejection_allowsRetry() {
        val paused = ScooterTelemetry(
            charging = false,
            chargerConnected = true,
            chargingStatus = "Paused",
            remoteChargingAction = "stop"
        )
        val rejected = RemoteChargingCommand(
            action = "start",
            phase = RemoteCommandPhase.ERROR,
            message = "Ather rejected the charging request.",
            requestedAt = t0
        )
        val view = ChargingControl.resolveView(paused, rejected, t0 + 1_000L)
        assertFalse(view.commandPending)
        assertTrue(view.canResume)
        assertFalse(view.canPause)
    }

    @Test
    fun timeoutWaitingForTelemetry_allowsRetry() {
        val stillCharging = ScooterTelemetry(
            charging = true,
            chargerConnected = true,
            chargingStatus = "Charging",
            remoteChargingAction = "start"
        )
        val acceptedStop = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.ACCEPTED,
            message = "Ather accepted the request; waiting for scooter confirmation.",
            requestedAt = t0
        )
        val timedOut = ChargingControl.advanceCommand(
            acceptedStop,
            stillCharging,
            nowMs = t0 + ChargingControl.CONFIRM_TIMEOUT_MS + 1L
        )
        assertEquals(RemoteCommandPhase.ERROR, timedOut.phase)
        assertTrue(timedOut.message!!.contains("Timed out"))

        val view = ChargingControl.resolveView(stillCharging, timedOut, t0 + ChargingControl.CONFIRM_TIMEOUT_MS + 2L)
        assertFalse(view.commandPending)
        assertTrue(view.canPause)
        assertFalse(view.canResume)
    }

    @Test
    fun timeoutStillReleasesLatchWhenTelemetrySocketIsMissing() {
        val accepted = RemoteChargingCommand(
            action = "start",
            phase = RemoteCommandPhase.ACCEPTED,
            requestedAt = t0
        )
        val waiting = ChargingControl.resolveView(null, accepted, t0 + 1_000L)
        assertTrue(waiting.commandPending)

        val timedOut = ChargingControl.resolveView(
            null,
            accepted,
            t0 + ChargingControl.CONFIRM_TIMEOUT_MS + 1L
        )
        assertEquals(RemoteCommandPhase.ERROR, timedOut.command.phase)
        assertFalse(timedOut.commandPending)
    }

    @Test
    fun unpluggedTelemetryDoesNotFlashFalseErrorWhileCommandIsPending() {
        val unplugged = ScooterTelemetry(
            charging = false,
            chargerConnected = false,
            chargingStatus = "Completed",
            remoteChargingAction = "stop"
        )
        val cmd = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.CONFIRMED,
            requestedAt = t0
        )
        val view = ChargingControl.resolveView(unplugged, cmd, t0 + 1_000L)
        assertFalse(view.pluggedIn)
        assertFalse(view.canPause)
        assertFalse(view.canResume)

        val pending = RemoteChargingCommand(
            action = "start",
            phase = RemoteCommandPhase.ACCEPTED,
            requestedAt = t0
        )
        val waiting = ChargingControl.advanceCommand(pending, unplugged, t0 + 2_000L)
        assertEquals(RemoteCommandPhase.ACCEPTED, waiting.phase)
    }

    @Test
    fun stopConfirmsWhenCurrentStopsDespiteTransientDisconnectedFlag() {
        val pendingStop = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.ACCEPTED,
            requestedAt = t0
        )
        val transition = ScooterTelemetry(
            charging = false,
            chargerConnected = false,
            chargingStatus = "Disconnected",
            remoteChargingAction = "stop"
        )
        val confirmed = ChargingControl.advanceCommand(pendingStop, transition, t0 + 2_000L)
        assertEquals(RemoteCommandPhase.CONFIRMED, confirmed.phase)
        assertEquals("Scooter confirmed charging stopped.", confirmed.message)
    }

    @Test
    fun startEchoAloneDoesNotMarkCharging() {
        val paused = ScooterTelemetry(
            charging = false,
            chargerConnected = true,
            chargingStatus = "Paused",
            remoteChargingAction = "stop"
        )
        val startEcho = ScooterTelemetry(remoteChargingAction = "start")
        val merged = paused.mergeWith(startEcho)

        assertEquals(false, merged.charging)
        assertEquals("Paused", merged.chargingStatus)
        assertEquals("start", merged.remoteChargingAction)

        val accepted = RemoteChargingCommand(
            action = "start",
            phase = RemoteCommandPhase.ACCEPTED,
            requestedAt = t0
        )
        // Shadow action=start is not confirmation — still waiting for current.
        val advanced = ChargingControl.advanceCommand(accepted, merged, t0 + 1_000L)
        assertEquals(RemoteCommandPhase.ACCEPTED, advanced.phase)
    }

    @Test
    fun stopKeepsPluggedSessionControllable() {
        val afterStop = ScooterTelemetry(
            charging = false,
            chargerConnected = true,
            chargingStatus = "Paused",
            remoteChargingAction = "stop"
        )
        assertTrue(ChargingControl.isPluggedIn(afterStop))
        assertTrue(ChargingControl.isPaused(afterStop))
        val view = ChargingControl.resolveView(afterStop, RemoteChargingCommand(), t0)
        assertTrue(view.canResume)
    }

    @Test fun stopEchoAloneNeverConfirmsOrTurnsOffAnActiveCharge() {
        val before = ScooterTelemetry(batterySoc = 79.0, charging = true, chargerConnected = true)
        val echo = ScooterTelemetry(remoteChargingAction = "stop")
        val merged = before.mergeWith(echo)
        assertTrue(ChargingControl.isActivelyCharging(merged))
        assertFalse(ChargingEvidence.hasChargeReading(echo))
        val pending = RemoteChargingCommand(action = "stop", phase = RemoteCommandPhase.ACCEPTED, requestedAt = t0)
        assertEquals(RemoteCommandPhase.ACCEPTED, ChargingControl.advanceCommand(pending, echo, t0 + 1_000).phase)
        assertEquals(RemoteCommandPhase.ACCEPTED, ChargingControl.advanceCommand(pending, merged, t0 + 1_000).phase)
    }

    @Test fun freshChargingReplacesAnOldDisconnectedFlagWithoutNeedingFullSnapshot() {
        val before = ScooterTelemetry(charging = false, chargerConnected = false, chargingStatus = "Disconnected")
        val merged = before.mergeWith(ScooterTelemetry(charging = true))
        assertTrue(ChargingControl.isPluggedIn(merged))
        assertTrue(ChargingControl.isActivelyCharging(merged))
    }
}
