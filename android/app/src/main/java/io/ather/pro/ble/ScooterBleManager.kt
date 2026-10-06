package io.ather.pro.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Application-lifetime direct BLE link to a scooter-side peripheral. Scans for
 * everything, flags scooter-like advertisements, connects on demand and
 * subscribes to every notify/indicate characteristic so unknown firmware can
 * be explored from the on-screen log before [BleParser] is taught the format.
 */
@SuppressLint("MissingPermission")
class ScooterBleManager(private val context: Context) {
    companion object {
        /** Common e-scooter UART service; also matched when advertised by name. */
        val UART_SERVICE = UUID.fromString("0000ffa0-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SCOOTER_HINTS = listOf("ather", "450", "rizta", "scooter")
    }

    private val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bm.adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val pendingCccds = ArrayDeque<BluetoothGattDescriptor>()

    private val _state = MutableStateFlow(BleUiState(adapterPresent = bm.adapter != null, adapterEnabled = bm.adapter?.isEnabled == true))
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private fun update(transform: (BleUiState) -> BleUiState) { _state.value = transform(_state.value) }

    private fun log(message: String) {
        Log.d("AtherMateBLE", message)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        update { it.copy(logs = (it.logs + "$stamp  $message").takeLast(200)) }
    }

    fun refreshAdapterState() = update { it.copy(adapterPresent = adapter != null, adapterEnabled = adapter?.isEnabled == true) }

    fun startScan() {
        refreshAdapterState()
        val a = adapter
        if (a?.bluetoothLeScanner == null) { log("Bluetooth is off or unavailable"); return }
        if (scanning) return
        update { it.copy(devices = emptyList(), connection = BleConnection.SCANNING, connectingTo = null) }
        a.bluetoothLeScanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        scanning = true
        log("Scanning for BLE devices…")
    }

    fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        if (_state.value.connection == BleConnection.SCANNING) update { it.copy(connection = BleConnection.DISCONNECTED) }
        log("Scan stopped")
    }

    fun connect(device: BleDeviceUi) {
        refreshAdapterState()
        val a = adapter
        if (a?.bluetoothLeScanner == null) { log("Bluetooth is off or unavailable"); return }
        stopScan()
        val d: BluetoothDevice = a.getRemoteDevice(device.address)
        log("Connecting ${device.name.ifBlank { device.address }}…")
        update { it.copy(connection = BleConnection.CONNECTING, connectingTo = device.name.ifBlank { device.address }) }
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else d.connectGatt(context, false, callback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        pendingCccds.clear()
        update { it.copy(connection = BleConnection.DISCONNECTED, connectingTo = null, snapshot = it.snapshot.copy(connected = false)) }
        log("Disconnected")
    }

    fun clearLogs() = update { it.copy(logs = emptyList()) }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device?.name ?: ""
            val scooterLike = name.lowercase().let { n -> SCOOTER_HINTS.any(n::contains) } ||
                    result.scanRecord?.serviceUuids?.any { it.uuid == UART_SERVICE } == true
            val seen = BleDeviceUi(name.ifBlank { "Unnamed" }, result.device.address, result.rssi, scooterLike)
            update { st ->
                val list = st.devices.toMutableList()
                val index = list.indexOfFirst { it.address == seen.address }
                if (index >= 0) list[index] = seen else list.add(seen)
                st.copy(devices = list.sortedWith(
                    compareByDescending<BleDeviceUi> { it.scooterLike }.thenByDescending { it.rssi }
                ).take(40))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            update { it.copy(connection = BleConnection.DISCONNECTED) }
            log("Scan failed (code $errorCode) — is location enabled?")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT connected, discovering services…")
                    update { it.copy(connection = BleConnection.CONNECTED) }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    gatt = null
                    pendingCccds.clear()
                    update { it.copy(connection = BleConnection.DISCONNECTED, connectingTo = null, snapshot = it.snapshot.copy(connected = false)) }
                    log("Device disconnected")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("Service discovery failed ($status)"); return }
            val notifyChars = g.services.flatMap { it.characteristics }
                .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            log("Found ${g.services.size} services, ${notifyChars.size} notify characteristics")
            pendingCccds.clear()
            notifyChars.forEach { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(CCCD)?.let(pendingCccds::add)
            }
            writeNextCccd(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Subscribe failed ${descriptor.characteristic.uuid} ($status)")
            writeNextCccd(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handle(characteristic.uuid, value)
        }

        @Deprecated("Pre-T channel")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") characteristic.value?.let { handle(characteristic.uuid, it) }
        }
    }

    /** GATT allows one pending write, so subscriptions are enabled one at a time. */
    @Suppress("DEPRECATION")
    private fun writeNextCccd(g: BluetoothGatt) {
        val descriptor = pendingCccds.poll() ?: run {
            update { it.copy(connection = BleConnection.CONNECTED) }
            return
        }
        val written: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        }
        if (!written) {
            log("Could not queue subscribe ${descriptor.characteristic.uuid}")
            writeNextCccd(g)
        }
    }

    private fun handle(uuid: UUID, data: ByteArray) {
        log("RX ${uuid.toString().take(8)} ${BleParser.toHex(data)}")
        update { it.copy(snapshot = BleParser.parse(uuid, data, it.snapshot)) }
    }
}
