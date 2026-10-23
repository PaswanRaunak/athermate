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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side GATT SERVER — the role the real Ather app runs in BLEServerService:
 * the dash ("Pair phone to control Calls, Music, WhatsApp") is the GATT CLIENT
 * that connects into the phone.
 *
 * Faithful to the decompiled BluetoothServer:
 *  - FIVE services hosted (we previously omitted TBT): CALL → MEDIA → SOCIAL →
 *    TBT → VOICE, each with NOTIFY_CHAR + COMMAND_CHAR (properties 0x1A,
 *    permissions 0x11) and a CCCD
 *  - services are added ONE AT A TIME, the next added in onServiceAdded — the
 *    real app's exact chain; bulk-adding can leave services dropped on some stacks
 *  - each payload type is notified on its OWN service's characteristic, not the
 *    call characteristic
 */
class VehicleGattServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnectionChanged: (device: BluetoothDevice?, connected: Boolean) -> Unit,
    private val onCommand: (command: Int, device: BluetoothDevice) -> Unit,
    private val onLog: (String) -> Unit
) {
    /** Real server order: CALL, MEDIA, SOCIAL, TBT, VOICE (BluetoothServer.addService chain). */
    private val serverChain = listOf(
        BleVehicleProfile.CALL_SERVICE,
        BleVehicleProfile.MEDIA_SERVICE,
        BleVehicleProfile.SOCIAL_SERVICE,
        BleVehicleProfile.TBT_SERVICE,
        BleVehicleProfile.VOICE_SERVICE
    )

    private var gattServer: BluetoothGattServer? = null
    private var client: BluetoothDevice? = null
    @Volatile private var allServicesAdded = false

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
        allServicesAdded = false
        server.clearServices()
        // Add only the first service; the chain continues in onServiceAdded.
        server.addService(buildService(BleVehicleProfile.CALL_SERVICE))
        onLog("GATT server created — adding services sequentially (CALL → MEDIA → SOCIAL → TBT → VOICE)")
        return true
    }

    private fun buildService(serviceUuid: UUID): BluetoothGattService {
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        listOf(BleVehicleProfile.NOTIFY_CHAR, BleVehicleProfile.COMMAND_CHAR).forEach { charUuid ->
            // Real GattManager.addGattService: properties 26 (READ|WRITE|NOTIFY), permissions 17.
            val ch = BluetoothGattCharacteristic(charUuid, 26, 17)
            ch.addDescriptor(BluetoothGattDescriptor(BleVehicleProfile.CCCD, 17))
            service.addCharacteristic(ch)
        }
        return service
    }

    fun stop() {
        synchronized(notifyLock) { notifyQueue.clear() }
        notifyJob?.cancel()
        client?.let { gattServer?.cancelConnection(it) }
        gattServer?.close()
        gattServer = null
        client = null
        allServicesAdded = false
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
                    notifyMutex.withLock { notifyChunked(device, type, bytes) }
                    onLog("sent payload t=$type (${bytes.size} B)")
                }
            } finally {
                notifying.set(false)
                synchronized(notifyLock) { if (notifyQueue.isNotEmpty()) drainQueue() }
            }
        }
    }

    /** Each payload type rides its OWN service's notify characteristic. */
    private fun serviceForType(type: Int): UUID = when (type) {
        BleVehicleProfile.PAYLOAD_PHONE_CALL, BleVehicleProfile.PAYLOAD_CONNECTION -> BleVehicleProfile.CALL_SERVICE
        BleVehicleProfile.PAYLOAD_MEDIA, BleVehicleProfile.PAYLOAD_ALBUM_ART -> BleVehicleProfile.MEDIA_SERVICE
        BleVehicleProfile.PAYLOAD_WHATSAPP -> BleVehicleProfile.SOCIAL_SERVICE
        BleVehicleProfile.PAYLOAD_TBT -> BleVehicleProfile.TBT_SERVICE
        BleVehicleProfile.PAYLOAD_VC_UPTIME, BleVehicleProfile.PAYLOAD_VOICE -> BleVehicleProfile.VOICE_SERVICE
        else -> BleVehicleProfile.CALL_SERVICE
    }

    /** Split to MTU-3 chunks; each chunk prefixed with [seq, moreFlag]. */
    private suspend fun notifyChunked(device: BluetoothDevice, type: Int, bytes: ByteArray) {
        val chunkSize = minOf(negotiatedMtu - 3, BleVehicleProfile.MAX_PAYLOAD_BYTES) - 2
        val chunks = if (bytes.size <= chunkSize) listOf(bytes)
        else bytes.toList().chunked(chunkSize).map { it.toByteArray() }
        chunks.forEachIndexed { i, chunk ->
            val payload = if (chunks.size == 1) chunk
            else byteArrayOf(i.toByte(), if (i == chunks.size - 1) 1 else 0) + chunk
            if (!sendNotify(device, type, payload)) { delay(50); sendNotify(device, type, payload) }
            delay(20)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendNotify(device: BluetoothDevice, type: Int, payload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val service = server.getService(serviceForType(type)) ?: return false
        val ch = service.getCharacteristic(BleVehicleProfile.NOTIFY_CHAR) ?: return false
        ch.value = payload
        return server.notifyCharacteristicChanged(device, ch, false)
    }

    // ---------------------------------------------------------------- callbacks

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            val uuid = service?.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("SERVICE ADD FAILED ($status) for $uuid — the dash will miss this service")
                return
            }
            onLog("service added: $uuid")
            val index = serverChain.indexOf(uuid)
            val next = serverChain.getOrNull(index + 1)
            if (next != null) {
                gattServer?.addService(buildService(next))
            } else if (uuid == BleVehicleProfile.VOICE_SERVICE) {
                allServicesAdded = true
                onLog("ALL ${serverChain.size} SERVICES HOSTED — dash can connect")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    client = device
                    onConnectionChanged(device, true)
                    onLog("vehicle connected: ${device.address}")
                    // Flush anything queued while the dash was away (Ather's queue
                    // behaves the same: payloads wait for the next connection).
                    drainQueue()
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
                onLog("vehicle subscribed to ${descriptor.characteristic?.uuid} of ${descriptor.characteristic?.service?.uuid}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (characteristic.uuid == BleVehicleProfile.COMMAND_CHAR && value.isNotEmpty()) {
                onLog("command from vehicle: ${value[0].toInt() and 0xFF}")
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
            if (offset > response.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response.copyOfRange(offset, response.size))
            }
        }
    }
}
