package io.ather.pro.ble

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bonding state machine, a direct port of Ather's BeaconManager:
 * IDLE -> PAIRING -> WAITING_FOR_REQUEST -> PAIRED | FAILED, with the 7-second
 * timeout guard and automatic confirmation via setPairingConfirmation (the dash
 * has no UI to confirm pairing, so the phone accepts on its behalf).
 */
class VehiclePairingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPaired: (BluetoothDevice) -> Unit,
    private val onFailed: (device: BluetoothDevice?, reason: String) -> Unit
) {
    sealed interface State {
        data object Idle : State
        data object Pairing : State
        data object WaitingForRequest : State
        data object Paired : State
        data class Failed(val reason: String) : State
    }

    private var state: State = State.Idle
    private var deviceToBond: BluetoothDevice? = null
    private var timeoutJob: Job? = null

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            when (newState) {
                BluetoothDevice.BOND_BONDING -> {
                    state = State.WaitingForRequest
                    // Auto-accept — Ather BeaconManager.setPairingConfirmation(true).
                    runCatching { device.setPairingConfirmation(true) }
                }
                BluetoothDevice.BOND_BONDED -> {
                    if (device.address == deviceToBond?.address) {
                        state = State.Paired
                        cancelTimeout()
                        onPaired(device)
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    if (device.address == deviceToBond?.address && state != State.Idle && state != State.Paired) {
                        fail("bond removed (prev=$prevState)")
                    }
                }
            }
        }
    }

    init {
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    fun startPairing(device: BluetoothDevice) {
        if (state is State.Pairing || state is State.WaitingForRequest || state is State.Paired) return
        state = State.Pairing
        deviceToBond = device
        startTimeout()
        if (device.bondState != BluetoothDevice.BOND_BONDING && device.bondState != BluetoothDevice.BOND_BONDED) {
            runCatching { device.createBond() }
                .onFailure { fail("createBond threw: ${it.message}") }
        }
    }

    fun cleanup() {
        cancelTimeout()
        deviceToBond = null
        state = State.Idle
    }

    fun unregister() {
        cleanup()
        runCatching { context.unregisterReceiver(bondReceiver) }
    }

    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch(Dispatchers.Default) {
            delay(BleVehicleProfile.PAIRING_TIMEOUT_MS)
            if (state is State.WaitingForRequest || state is State.Pairing) {
                fail("pairing timed out after ${BleVehicleProfile.PAIRING_TIMEOUT_MS / 1000} s")
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun fail(reason: String) {
        cancelTimeout()
        state = State.Failed(reason)
        onFailed(deviceToBond, reason)
        state = State.Idle
        deviceToBond = null
    }
}
