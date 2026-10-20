package io.ather.pro.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Queue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side GATT CLIENT — connects to the vehicle, discovers its services,
 * verifies the profile, subscribes in the fixed order Call -> Media -> Social
 * -> Voice, requests MTU 517, and writes payloads/commands through a retried
 * queue (Ather's CommandQueueManager: priority + normal lanes).
 */
class VehicleGattClientManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (state: ClientState, device: BluetoothDevice?) -> Unit,
    private val onPayload: (type: Int, dataJson: String) -> Unit,
    private val onCommand: (command: Int) -> Unit,
    private val onLog: (String) -> Unit
) {
    enum class ClientState { DISCONNECTED, CONNECTING, DISCOVERING, SUBSCRIBING, CONNECTED, PROFILE_MISMATCH }

    private var gatt: BluetoothGatt? = null
    private val subscribed = LinkedHashSet<UUID>()

    private data class Write(val bytes: ByteArray, val priority: Boolean, val retriesLeft: Int = 3)
    private val writeQueue: Queue<Write> = ArrayDeque()
    private val writing = AtomicBoolean(false)
    private var writeJob: Job? = null

    // ---------------------------------------------------------------- connect flow

    fun connect(device: BluetoothDevice) {
        disconnect()
        setState(ClientState.CONNECTING, device)
        // Ather GattManager.createClient: connectGatt(ctx, false, cb, TRANSPORT_LE).
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    fun disconnect() {
        writeQueue.clear()
        subscribed.clear()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        setState(ClientState.DISCONNECTED, null)
    }

    /** Phone -> vehicle command byte (Ather CommandQueueManager.addCommands). */
    fun sendCommand(command: Int, priority: Boolean = true) {
        enqueue(byteArrayOf(command.toByte()), priority)
    }

    fun sendPayload(type: Int, bytes: ByteArray, priority: Boolean = false) {
        enqueue(bytes, priority)
    }

    private fun enqueue(bytes: ByteArray, priority: Boolean) {
        synchronized(writeQueue) {
            if (priority) (writeQueue as ArrayDeque<Write>).addFirst(Write(bytes, priority)) else writeQueue.add(Write(bytes, priority))
        }
        drainWrites()
    }

    private fun drainWrites() {
        if (writing.getAndSet(true)) return
        writeJob = scope.launch {
            try {
                while (true) {
                    val w = synchronized(writeQueue) { writeQueue.poll() } ?: break
                    val target = gatt ?: break
                    if (!writeOne(target, w)) delay(60)
                    delay(30)
                }
            } finally {
                writing.set(false)
                synchronized(writeQueue) { if (writeQueue.isNotEmpty()) drainWrites() }
            }
        }
    }

    private fun writeOne(target: BluetoothGatt, w: Write): Boolean {
        val characteristic = BleVehicleProfile.SUBSCRIBE_ORDER.firstNotNullOfOrNull { svc ->
            target.getService(svc)?.getCharacteristic(BleVehicleProfile.COMMAND_CHAR)
        } ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            target.writeCharacteristic(characteristic, w.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = w.bytes
            @Suppress("DEPRECATION")
            target.writeCharacteristic(characteristic)
        }
        return result == BluetoothGatt.GATT_SUCCESS || result == 0
    }

    private fun setState(s: ClientState, device: BluetoothDevice?) = onStateChanged(s, device)

    // ---------------------------------------------------------------- callbacks

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    setState(ClientState.DISCOVERING, g.device)
                    // Ather waits 500 ms before discoverServices (handleConnection$2).
                    scope.launch { delay(500); runCatching { g.discoverServices() } }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    onLog("disconnected from vehicle (status=$status)")
                    setState(ClientState.DISCONNECTED, g.device)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("service discovery failed ($status)")
                g.disconnect(); return
            }
            val services = g.services.map { it.uuid }
            if (!services.containsAll(BleVehicleProfile.SUBSCRIBE_ORDER)) {
                onLog("profile mismatch: vehicle lacks required services")
                setState(ClientState.PROFILE_MISMATCH, g.device)
                g.disconnect(); return
            }
            setState(ClientState.SUBSCRIBING, g.device)
            // Begin the subscription chain at Call (AndroidClient.enableDescriptor).
            subscribeNext(g, BleVehicleProfile.CALL_SERVICE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("descriptor write failed ($status)")
                if (status == 245 || status == 15 || status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                    onLog("authentication required ($status) — triggering OS Bluetooth pairing…")
                    runCatching { g.device.createBond() }
                }
                return
            }
            val serviceUuid = descriptor.characteristic?.service?.uuid
            onLog("subscribed: $serviceUuid")
            when (serviceUuid) {
                BleVehicleProfile.VOICE_SERVICE ->
                    // Chain complete — MTU last, exactly as AndroidClient.enableDescriptor.
                    g.requestMtu(BleVehicleProfile.REQUEST_MTU)
                else -> subscribeNext(g, nextAfter(serviceUuid))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onLog("MTU: $mtu (${if (mtu >= 500) "full 500-B payloads OK" else "reduced — payloads will be chunked"})")
            setState(ClientState.CONNECTED, g.device)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(characteristic, value)
        }

        @Deprecated("legacy path")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleIncoming(characteristic, it) }
            }
        }
    }

    private fun handleIncoming(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == BleVehicleProfile.COMMAND_CHAR) {
            if (value.isNotEmpty()) onCommand(value[0].toInt() and 0xFF)
            return
        }
        val decoded = VehiclePayloadCodec.decode(stripFraming(value)) ?: return
        onPayload(decoded.type, decoded.data.toString())
    }

    private fun stripFraming(value: ByteArray): ByteArray {
        // Single-chunk payloads are raw JSON; chunked ones carry [seq, more] first.
        return if (value.size > 2 && value[1].toInt() in 0..1 && value[0].toInt() in 0..127 && value[2] != '{'.code.toByte()) {
            value.copyOfRange(2, value.size)
        } else value
    }

    private fun nextAfter(uuid: UUID?): UUID = when (uuid) {
        BleVehicleProfile.CALL_SERVICE -> BleVehicleProfile.MEDIA_SERVICE
        BleVehicleProfile.MEDIA_SERVICE -> BleVehicleProfile.SOCIAL_SERVICE
        else -> BleVehicleProfile.VOICE_SERVICE
    }

    private fun subscribeNext(g: BluetoothGatt, serviceUuid: UUID) {
        val service = g.getService(serviceUuid) ?: run {
            onLog("missing service $serviceUuid — skipping")
            subscribeNext(g, nextAfter(serviceUuid)); return
        }
        val ch = service.getCharacteristic(BleVehicleProfile.NOTIFY_CHAR) ?: run {
            subscribeNext(g, nextAfter(serviceUuid)); return
        }
        runCatching { g.setCharacteristicNotification(ch, true) }
        val descriptor = ch.getDescriptor(BleVehicleProfile.CCCD) ?: run {
            subscribeNext(g, nextAfter(serviceUuid)); return
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        } == 0
        if (!ok) onLog("subscribe request to $serviceUuid rejected")
    }
}
