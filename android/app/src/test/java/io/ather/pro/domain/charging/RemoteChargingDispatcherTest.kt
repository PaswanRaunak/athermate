package io.ather.pro.domain.charging

import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level fakes: each button dispatches exactly one correct command;
 * stop→paused→start→charging; stale delta / rejection / timeout. No network.
 */
class RemoteChargingDispatcherTest {

    private val t0 = 5_000_000L
    private var now = t0

    private class FakeGateway : RemoteChargingGateway {
        data class Call(val token: String, val uuid: String, val start: Boolean)
        val calls = mutableListOf<Call>()
        var nextResult: Result<Unit> = Result.success(Unit)
        var lastCallback: ((Result<Unit>) -> Unit)? = null

        override fun setRemoteCharging(
            token: String,
            scooterUuid: String,
            start: Boolean,
            callback: (Result<Unit>) -> Unit
        ) {
            calls += Call(token, scooterUuid, start)
            lastCallback = callback
            callback(nextResult)
        }
    }

    private fun charging() = ScooterTelemetry(
        charging = true,
        chargerConnected = true,
        chargingStatus = "Charging",
        batterySoc = 55.0
    )

    private fun paused() = ScooterTelemetry(
        charging = false,
        chargerConnected = true,
        chargingStatus = "Paused",
        remoteChargingAction = "stop",
        batterySoc = 56.0
    )

    private fun dispatcher(gateway: FakeGateway) = RemoteChargingDispatcher(
        gateway = gateway,
        nowMs = { now },
        confirmTimeoutMs = 1_000L
    )

    @Test
    fun stopButton_dispatchesExactlyOneStop() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val attempt = d.attempt(
            start = false,
            token = "jwt-1",
            scooterUuid = "scooter-1",
            telemetry = charging(),
            current = RemoteChargingCommand(),
            onHttpResult = {}
        )
        assertTrue(attempt.dispatched)
        assertEquals("stop", attempt.action)
        assertEquals("scooter-1", attempt.scooterUuid)
        assertEquals(1, gw.calls.size)
        assertFalse(gw.calls.single().start)
        assertEquals("jwt-1", gw.calls.single().token)
        assertEquals(RemoteCommandPhase.SENDING, attempt.command.phase)
    }

    @Test
    fun startButton_dispatchesExactlyOneStart() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val attempt = d.attempt(
            start = true,
            token = "jwt-2",
            scooterUuid = "scooter-2",
            telemetry = paused(),
            current = RemoteChargingCommand(),
            onHttpResult = {}
        )
        assertTrue(attempt.dispatched)
        assertEquals("start", attempt.action)
        assertEquals(1, gw.calls.size)
        assertTrue(gw.calls.single().start)
    }

    @Test
    fun stopPausedStartCharging_sequenceWithTelemetryConfirm() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        var command = RemoteChargingCommand()

        // Stop while charging
        var attempt = d.attempt(false, "jwt", "uuid", charging(), command) {}
        assertTrue(attempt.dispatched)
        command = attempt.command
        command = d.applyHttpResult(command, "stop", Result.success(Unit))
        assertEquals(RemoteCommandPhase.ACCEPTED, command.phase)

        // Telemetry confirms pause
        command = ChargingControl.advanceCommand(command, paused(), now + 100L, 1_000L)
        assertEquals(RemoteCommandPhase.CONFIRMED, command.phase)
        val pausedView = ChargingControl.resolveView(paused(), command, now + 100L)
        assertTrue(pausedView.canResume)
        assertFalse(pausedView.canPause)

        // Start / resume
        attempt = d.attempt(true, "jwt", "uuid", paused(), command) {}
        assertTrue(attempt.dispatched)
        assertEquals(2, gw.calls.size)
        assertTrue(gw.calls.last().start)
        command = attempt.command
        command = d.applyHttpResult(command, "start", Result.success(Unit))
        assertEquals(RemoteCommandPhase.ACCEPTED, command.phase)

        command = ChargingControl.advanceCommand(command, charging(), now + 200L, 1_000L)
        assertEquals(RemoteCommandPhase.CONFIRMED, command.phase)
        val chargingView = ChargingControl.resolveView(charging(), command, now + 200L)
        assertTrue(chargingView.canPause)
        assertFalse(chargingView.canResume)
    }

    @Test
    fun liveChargingAfterConfirmedStop_enablesPauseNotResume() {
        val confirmedStop = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.CONFIRMED,
            message = "paused",
            requestedAt = t0
        )
        // Bike is charging again — treat as a new session, enable Stop.
        val live = ScooterTelemetry(
            charging = true,
            chargerConnected = true,
            chargingStatus = "Charging",
            remoteChargingAction = "stop",
            batterySoc = 60.0
        )
        val view = ChargingControl.resolveView(live, confirmedStop, t0)
        assertTrue(view.canPause)
        assertFalse(view.canResume)
        val gw = FakeGateway()
        val attempt = dispatcher(gw).attempt(false, "jwt", "uuid", live, confirmedStop) {}
        assertTrue(attempt.dispatched)
        assertFalse(gw.calls.single().start)
    }

    @Test
    fun httpRejection_surfacesErrorAndAllowsRetry() {
        val gw = FakeGateway().apply {
            nextResult = Result.failure(IllegalStateException("Ather command HTTP 403"))
        }
        val d = dispatcher(gw)
        var command = RemoteChargingCommand()
        val attempt = d.attempt(false, "jwt", "uuid", charging(), command) {}
        assertTrue(attempt.dispatched)
        command = d.applyHttpResult(attempt.command, "stop", gw.nextResult)
        assertEquals(RemoteCommandPhase.ERROR, command.phase)
        assertTrue(command.message!!.contains("403"))

        // Clear latch + retry dispatches exactly one more stop
        gw.nextResult = Result.success(Unit)
        command = d.clearLatch()
        val retry = d.attempt(false, "jwt", "uuid", charging(), command) {}
        assertTrue(retry.dispatched)
        assertEquals(2, gw.calls.size)
    }

    @Test
    fun confirmTimeout_clearsPendingBrick_thenRetryDispatches() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        var command = d.attempt(false, "jwt", "uuid", charging(), RemoteChargingCommand()) {}.command
        command = d.applyHttpResult(command, "stop", Result.success(Unit))
        assertEquals(RemoteCommandPhase.ACCEPTED, command.phase)

        // Still charging — not confirmed; advance past timeout
        now = t0 + 5_000L
        command = ChargingControl.advanceCommand(command, charging(), now, 1_000L)
        assertEquals(RemoteCommandPhase.ERROR, command.phase)

        val view = ChargingControl.resolveView(charging(), command, now, 1_000L)
        assertFalse(view.commandPending)
        assertTrue(view.canPause)

        val retry = d.attempt(false, "jwt", "uuid", charging(), command) {}
        assertTrue(retry.dispatched)
        assertEquals(2, gw.calls.size)
    }

    @Test
    fun repeatedManualActionWhilePending_dispatchesFreshRequest() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val first = d.attempt(false, "jwt", "uuid", charging(), RemoteChargingCommand()) {}
        assertTrue(first.dispatched)
        val accepted = d.applyHttpResult(first.command, "stop", Result.success(Unit))
        val second = d.attempt(
            start = false,
            token = "jwt",
            scooterUuid = "uuid",
            telemetry = charging(),
            current = accepted,
            supersedePending = true
        ) {}
        assertTrue(second.dispatched)
        assertEquals(2, gw.calls.size)
        assertTrue(second.command.requestedAt != null)
    }

    @Test
    fun oppositeActionMaySupersedePending() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val first = d.attempt(false, "jwt", "uuid", charging(), RemoteChargingCommand()) {}
        val accepted = d.applyHttpResult(first.command, "stop", Result.success(Unit))
        val reverse = d.attempt(
            start = true,
            token = "jwt",
            scooterUuid = "uuid",
            telemetry = charging(),
            current = accepted,
            supersedePending = true
        ) {}
        assertTrue(reverse.dispatched)
        assertEquals(2, gw.calls.size)
        assertTrue(gw.calls.last().start)
    }

    @Test
    fun withoutSupersede_pendingBlocksSecondDispatch() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val first = d.attempt(false, "jwt", "uuid", charging(), RemoteChargingCommand()) {}
        val accepted = d.applyHttpResult(first.command, "stop", Result.success(Unit))
        val second = d.attempt(
            start = false,
            token = "jwt",
            scooterUuid = "uuid",
            telemetry = charging(),
            current = accepted,
            supersedePending = false
        ) {}
        assertFalse(second.dispatched)
        assertEquals(1, gw.calls.size)
    }

    @Test
    fun confirmedStopThenLiveCharging_enablesPause() {
        val confirmed = RemoteChargingCommand(
            action = "stop",
            phase = RemoteCommandPhase.CONFIRMED,
            requestedAt = t0
        )
        val view = ChargingControl.resolveView(charging(), confirmed, t0)
        assertTrue(view.activelyCharging)
        assertTrue(view.canPause)
        assertFalse(view.canResume)
    }

    @Test
    fun missingCredentials_doesNotDispatch() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val attempt = d.attempt(false, null, "uuid", charging(), RemoteChargingCommand()) {}
        assertFalse(attempt.dispatched)
        assertEquals(0, gw.calls.size)
        assertEquals(RemoteCommandPhase.ERROR, attempt.command.phase)
    }

    @Test
    fun staleOrMissingTelemetry_doesNotBlockManualCommand() {
        val gw = FakeGateway()
        val d = dispatcher(gw)
        val stop = d.attempt(false, "jwt", "uuid", null, RemoteChargingCommand()) {}
        assertTrue(stop.dispatched)
        assertEquals(1, gw.calls.size)
        assertFalse(gw.calls.single().start)

        val unplugged = ScooterTelemetry(
            charging = false,
            chargerConnected = false,
            chargingStatus = "Completed"
        )
        val start = d.attempt(true, "jwt", "uuid", unplugged, RemoteChargingCommand()) {}
        assertTrue(start.dispatched)
        assertEquals(2, gw.calls.size)
        assertTrue(gw.calls.last().start)
        assertFalse(start.command.message.orEmpty().contains("unplug", ignoreCase = true))
        assertFalse(start.command.message.orEmpty().contains("disconnect", ignoreCase = true))
    }
}
