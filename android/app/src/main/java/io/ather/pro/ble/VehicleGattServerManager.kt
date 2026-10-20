package io.ather.pro.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side GATT SERVER — the role the Ather app runs in BLEServerService so
 * the dash can connect in (dash = GATT client). Kept for the vehicle-simulator
 * role used in two-device testing, mirroring BluetoothServer:
 *
 *  - the profile services with READ|WRITE|NOTIFY characteristics + CCCD
 *    (properties 0x1A, permissions 0x11)
 *  - a notify queue drained one payload at a time, waiting for
 *    onNotificationSent between payloads, capped at 500 bytes
 *  - command bytes dispatched like BluetoothServer.extractCommands
 */
class VehicleGattServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnectionChanged: (device: BluetoothDevice?, connected: Boolean) -> Unit,
    private val onCommand: (command: Int, device: BluetoothDevice) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var gattServer: BluetoothGattServer? = null
    private var client: BluetoothDevice? = null

    private val notifyLock = Any()
    private val notifyQueue: Queue<Pair<Int, ByteArray>> = ArrayDeque()
    private var notifyJob: Job? = null
    private val notifying = AtomicBoolean(false)
    private var negotiatedMtu = BleVehicleProfile.MAX_PAYLOAD_BYTES
    private val notifyMutex = Mutex()

    // ---------------------------------------------------------------- lifecycle

    fun start(): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val server = bm.openGattServer(context, serverCallback) ?: return false
        gattServer = server
        BleVehicleProfile.SUBSCRIBE_ORDER.forEach { serviceUuid ->
            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            listOf(BleVehicleProfile.NOTIFY_CHAR, BleVehicleProfile.COMMAND_CHAR).forEach { charUuid ->
                // Ather: properties 26 (READ|WRITE|NOTIFY), permissions 17 (READ|WRITE).
                val ch = BluetoothGattCharacteristic(charUuid, 26, 17)
                ch.addDescriptor(BluetoothGattDescriptor(BleVehicleProfile.CCCD, 17))
                service.addCharacteristic(ch)
            }
            server.addService(service)
        }
        onLog("GATT server started with ${BleVehicleProfile.SUBSCRIBE_ORDER.size} services")
        return true
    }

    fun stop() {
        synchronized(notifyLock) { notifyQueue.clear() }
        notifyJob?.cancel()
        client?.let { gattServer?.cancelConnection(it) }
        gattServer?.close()
        gattServer = null
        client = null
    }

    // ---------------------------------------------------------------- public send API

    /** Queue a typed JSON payload for the connected vehicle (Ather addDataToNotifyData). */
    fun sendPayload(type: Int, bytes: ByteArray) {
        synchronized(notifyLock) { notifyQueue.add(type to bytes) }
        drainQueue()
    }

    fun clearPending() { synchronized(notifyLock) { notifyQueue.clear() } }

    private fun drainQueue() {
        if (notifying.getAndSet(true)) return
        notifyJob = scope.launch {
            try {
                while (true) {
                    val (type, bytes) = synchronized(notifyLock) { notifyQueue.poll() } ?: break
                    val device = client ?: break
                    notifyMutex.withLock { notifyChunked(device, bytes) }
                    onLog("sent payload t=$type (${bytes.size} B)")
                }
            } finally {
                notifying.set(false)
                synchronized(notifyLock) { if (notifyQueue.isNotEmpty()) drainQueue() }
            }
        }
    }

    /** Split to MTU-3 chunks; each chunk prefixed with [seq, moreFlag]. */
    private suspend fun notifyChunked(device: BluetoothDevice, bytes: ByteArray) {
        val chunkSize = minOf(negotiatedMtu - 3, BleVehicleProfile.MAX_PAYLOAD_BYTES) - 2
        val chunks = if (bytes.size <= chunkSize) listOf(bytes)
        else bytes.toList().chunked(chunkSize).map { it.toByteArray() }
        chunks.forEachIndexed { i, chunk ->
            val payload = if (chunks.size == 1) chunk
            else byteArrayOf(i.toByte(), if (i == chunks.size - 1) 1 else 0) + chunk
            if (!sendNotify(device, payload)) { delay(50); sendNotify(device, payload) }
            delay(20)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendNotify(device: BluetoothDevice, payload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val service = server.getService(BleVehicleProfile.CALL_SERVICE) ?: return false
        val ch = service.getCharacteristic(BleVehicleProfile.NOTIFY_CHAR) ?: return false
        ch.value = payload
        return server.notifyCharacteristicChanged(device, ch, false)
    }

    // ---------------------------------------------------------------- callbacks

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    client = device
                    onConnectionChanged(device, true)
                    onLog("vehicle connected: ${device.address}")
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    if (client?.address == device.address) {
                        client = null
                        onConnectionChanged(device, false)
                        onLog("vehicle disconnected (status=$status)")
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
            onLog("MTU negotiated: $mtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (descriptor.uuid == BleVehicleProfile.CCCD) {
                onLog("vehicle subscribed to ${descriptor.characteristic?.uuid}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (characteristic.uuid == BleVehicleProfile.COMMAND_CHAR && value.isNotEmpty()) {
                onCommand(value[0].toInt() and 0xFF, device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            // Minimal static read response (Ather serves identity info here).
            val response = JsonObject().apply {
                addProperty("name", "athermate-sim")
                addProperty("profile", "athermate/1.0")
            }.toString().toByteArray(Charsets.UTF_8)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }
    }
}
