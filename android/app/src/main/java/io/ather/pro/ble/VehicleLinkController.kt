package io.ather.pro.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * App-scoped orchestrator of the vehicle BLE link (Ather-app role):
 * scan for the vehicle beacon -> bond -> GATT client -> subscribe -> MTU ->
 * push payloads, receive commands. Auto-reconnects to the last bonded vehicle
 * when Bluetooth turns on, when the app starts with the link enabled, and
 * after boot (via [io.ather.pro.service.BootReceiver]).
 *
 * The wire protocol lives in [BleVehicleProfile]/[VehiclePayloadCodec]; the
 * reference implementation is the reverse-engineered Ather 13.5.1 app.
 */
@SuppressLint("MissingPermission")
class VehicleLinkController(private val context: Context) {

    enum class LinkStatus { OFF, SCANNING, PAIRING, CONNECTING, CONNECTED, PROFILE_MISMATCH }

    data class LinkUiState(
        val enabled: Boolean = false,
        val token: String = "",
        val link: LinkStatus = LinkStatus.OFF,
        val deviceName: String? = null,
        val deviceAddress: String? = null,
        val ignitionOn: Boolean = false,
        val callFeedActive: Boolean = false,
        val lastError: String? = null,
        val logs: List<String> = emptyList()
    )

    private val prefs = context.getSharedPreferences(BleVehicleProfile.PREFS_FILE, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val leScanner get() = adapter?.bluetoothLeScanner
    private val advertiser get() = adapter?.bluetoothLeAdvertiser
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val _state = MutableStateFlow(LinkUiState(enabled = prefs.getBoolean(BleVehicleProfile.PREF_LINK_ENABLED, false), token = loadOrCreateToken()))
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    private var scanner: VehicleBeaconScanner? = null
    private var pairing: VehiclePairingManager? = null
    private var gattClient: VehicleGattClientManager? = null
    private var keepaliveJob: Job? = null
    private val callStateFeed = AtomicReference<Any?>(null)

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (_state.value.enabled) {
                    log("Bluetooth on — reconnecting")
                    startInternal(autoReconnect = true)
                }
                BluetoothAdapter.STATE_TURNING_OFF -> stopInternal()
            }
        }
    }

    init {
        context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        if (_state.value.enabled && adapter?.isEnabled == true) startInternal(autoReconnect = true)
    }

    private fun update(transform: (LinkUiState) -> LinkUiState) { _state.value = transform(_state.value) }

    private fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        update { it.copy(logs = (it.logs + "$stamp  $message").takeLast(200)) }
    }

    private fun setLink(s: LinkStatus, device: BluetoothDevice? = null) = update {
        it.copy(link = s, deviceName = device?.name ?: it.deviceName, deviceAddress = device?.address ?: it.deviceAddress)
    }

    private fun loadOrCreateToken(): String {
        prefs.getString(BleVehicleProfile.PREF_TOKEN, null)?.let { return it }
        val token = VehicleBeaconAdvertiser.randomToken()
        prefs.edit().putString(BleVehicleProfile.PREF_TOKEN, token).apply()
        return token
    }

    /** QR/deep-link pairing: myvehicle://bluetooth/pair?token=… (Ather's token delivery). */
    fun registerToken(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString(BleVehicleProfile.PREF_TOKEN, token).apply()
        update { it.copy(token = token) }
        log("pairing token registered: $token")
        if (_state.value.enabled) { restart() }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(BleVehicleProfile.PREF_LINK_ENABLED, on).apply()
        update { it.copy(enabled = on) }
        if (on) startInternal(autoReconnect = true) else stopInternal()
    }

    fun restart() { stopInternal(); if (_state.value.enabled) startInternal(autoReconnect = true) }

    /**
     * Connect the profile client straight to a scanned device, bypassing the
     * token-beacon search. Bonds first when needed, then runs the standard
     * subscribe chain — if the vehicle lacks the profile it ends in
     * PROFILE_MISMATCH, which is itself a useful answer.
     */
    fun connectDirect(address: String) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: run {
            log("connectDirect: no device for $address (Bluetooth off?)"); return
        }
        scanner?.stop(leScanner)
        log("direct connect to ${device.address}")
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            setLink(LinkStatus.CONNECTING, device)
            ensureClient().connect(device)
        } else {
            setLink(LinkStatus.PAIRING, device)
            ensurePairing().startPairing(device)
        }
    }

    // ---------------------------------------------------------------- start / stop

    private fun startInternal(autoReconnect: Boolean) {
        val a = adapter
        if (a == null || !a.isEnabled) { log("Bluetooth is off — link idle until Bluetooth turns on"); return }
        VehicleBeaconScanner.TOKEN = _state.value.token

        // Reconnect first: Ather's auto path is bonded-last-device -> connectToGatt.
        if (autoReconnect && connectLastBonded()) return

        scanner = VehicleBeaconScanner(
            onBeaconFound = { device, token, rssi, bonded ->
                scope.launch {
                    log("beacon found: ${device.address} token=$token rssi=$rssi bonded=$bonded")
                    scanner?.stop(leScanner)
                    if (bonded) {
                        setLink(LinkStatus.CONNECTING, device)
                        ensureClient().connect(device)
                    } else {
                        setLink(LinkStatus.PAIRING, device)
                        ensurePairing().startPairing(device)
                    }
                }
            },
            onError = { msg -> update { it.copy(lastError = msg) }; log("scan error: $msg") }
        )
        setLink(LinkStatus.SCANNING)
        runCatching { scanner?.start(leScanner!!, _state.value.token) }
            .onFailure { log("scanner start failed: ${it.message}") }

        // Advertise the encrypted token so the dash can find the phone (advertiseBeacon).
        advertiser?.let {
            val enc = VehicleBeaconAdvertiser.startEncryptedToken(it, _state.value.token)
            log("advertising encrypted token: $enc")
        } ?: log("no LE advertiser on this device (phone beacon skipped)")

        io.ather.pro.service.VehicleLinkService.start(context)
    }

    private fun stopInternal() {
        scanner?.stop(leScanner); scanner = null
        pairing?.unregister(); pairing = null
        gattClient?.disconnect(); gattClient = null
        VehicleBeaconAdvertiser.stop(advertiser)
        keepaliveJob?.cancel(); keepaliveJob = null
        unregisterCallFeed()
        io.ather.pro.service.VehicleLinkService.stop(context)
        setLink(LinkStatus.OFF, null)
        update { it.copy(ignitionOn = false) }
    }

    /** Reconnect to the last bonded vehicle without scanning (Ather LastConnectedDeviceAddress). */
    private fun connectLastBonded(): Boolean {
        val address = prefs.getString(BleVehicleProfile.PREF_LAST_CONNECTED, null) ?: return false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        if (device.bondState != BluetoothDevice.BOND_BONDED) return false
        setLink(LinkStatus.CONNECTING, device)
        log("reconnecting to last vehicle $address")
        ensureClient().connect(device)
        return true
    }

    private fun rememberLastConnected(device: BluetoothDevice) {
        prefs.edit().putString(BleVehicleProfile.PREF_LAST_CONNECTED, device.address).apply()
    }

    // ---------------------------------------------------------------- pair / client

    private fun ensurePairing(): VehiclePairingManager {
        pairing?.let { return it }
        return VehiclePairingManager(
            context, scope,
            onPaired = { device ->
                log("PAIRED with ${device.address}")
                setLink(LinkStatus.CONNECTING, device)
                ensureClient().connect(device)
            },
            onFailed = { _, reason ->
                log("pairing FAILED ($reason)")
                update { it.copy(lastError = reason) }
                setLink(LinkStatus.SCANNING)
                runCatching { scanner?.start(leScanner!!, _state.value.token) }
            }
        ).also { pairing = it }
    }

    private fun ensureClient(): VehicleGattClientManager {
        gattClient?.let { return it }
        return VehicleGattClientManager(
            context, scope,
            onStateChanged = { s, device ->
                when (s) {
                    VehicleGattClientManager.ClientState.CONNECTED -> {
                        device?.let { rememberLastConnected(it) }
                        setLink(LinkStatus.CONNECTED, device)
                        // t=0 connection handshake, like Ather's ConnectionModel push.
                        gattClient?.sendPayload(
                            BleVehicleProfile.PAYLOAD_CONNECTION,
                            VehiclePayloadCodec.connection(
                                btName = Build.MODEL,
                                model = Build.MANUFACTURER,
                                os = "Android ${Build.VERSION.RELEASE}",
                                libVersion = "athermate/1.0"
                            ),
                            priority = true
                        )
                        registerCallFeed()
                        startKeepalive()
                    }
                    VehicleGattClientManager.ClientState.PROFILE_MISMATCH -> {
                        setLink(LinkStatus.PROFILE_MISMATCH, device)
                        update { it.copy(lastError = "vehicle lacks the expected services") }
                    }
                    VehicleGattClientManager.ClientState.DISCONNECTED -> {
                        unregisterCallFeed()
                        keepaliveJob?.cancel(); keepaliveJob = null
                        if (_state.value.link == LinkStatus.CONNECTED) {
                            setLink(LinkStatus.OFF, null)
                            if (_state.value.enabled) { log("restarting scan…"); startInternal(autoReconnect = true) }
                        }
                    }
                    else -> {}
                }
            },
            onPayload = { type, json -> log("payload from vehicle: t=$type $json") },
            onCommand = { cmd -> handleCommand(cmd) },
            onLog = { log(it) }
        ).also { gattClient = it }
    }

    // ---------------------------------------------------------------- command handling

    private fun handleCommand(cmd: Int) {
        val label = when (cmd) {
            BleVehicleProfile.CMD_STOP_NAVIGATION -> "STOP_NAVIGATION"
            BleVehicleProfile.CMD_MEDIA_PREVIOUS -> "MEDIA_PREVIOUS"
            BleVehicleProfile.CMD_MEDIA_TOGGLE -> "MEDIA_TOGGLE"
            BleVehicleProfile.CMD_MEDIA_NEXT -> "MEDIA_NEXT"
            BleVehicleProfile.CMD_VOLUME_UP -> "VOLUME_UP"
            BleVehicleProfile.CMD_VOLUME_DOWN -> "VOLUME_DOWN"
            BleVehicleProfile.CMD_CALL_ACCEPT -> "CALL_ACCEPT"
            BleVehicleProfile.CMD_CALL_REJECT -> "CALL_REJECT"
            BleVehicleProfile.CMD_CALL_REJECT_WITH_MESSAGE -> "CALL_REJECT_WITH_SMS"
            BleVehicleProfile.CMD_UNPAIR -> {
                prefs.edit().remove(BleVehicleProfile.PREF_LAST_CONNECTED).apply()
                restart(); "UNPAIR"
            }
            BleVehicleProfile.CMD_DISCONNECT_CLIENT -> { gattClient?.disconnect(); "DISCONNECT" }
            BleVehicleProfile.CMD_KEY_SWITCH_ON -> { update { it.copy(ignitionOn = true) }; "KEY_SWITCH_ON (ignition!)" }
            BleVehicleProfile.CMD_KEY_SWITCH_OFF -> {
                update { it.copy(ignitionOn = false) }
                gattClient?.sendPayload(BleVehicleProfile.PAYLOAD_COMMAND, JsonObject().toString().toByteArray())
                "KEY_SWITCH_OFF"
            }
            else -> "UNKNOWN_$cmd"
        }
        log("command: $label")
        // Media/call command dispatch onto the OS (AudioManager/MediaController)
        // lands with the notification-listener + media-session feeds.
    }

    // ---------------------------------------------------------------- outgoing payloads

    /** Manual/demo pushes while testing against a dash or a second phone. */
    fun sendCall(name: String, ringing: Boolean) =
        gattClient?.sendPayload(BleVehicleProfile.PAYLOAD_PHONE_CALL, VehiclePayloadCodec.phoneCall(name, if (ringing) 1 else 0, false), priority = true)

    fun sendMedia(track: String, artist: String) =
        gattClient?.sendPayload(BleVehicleProfile.PAYLOAD_MEDIA, VehiclePayloadCodec.media(track, artist, "playing", 0, 0))

    fun sendTbt(instruction: String, maneuver: Int, meters: Int) =
        gattClient?.sendPayload(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(maneuver, instruction, meters))

    fun sendWhatsapp(from: String, message: String) =
        gattClient?.sendPayload(BleVehicleProfile.PAYLOAD_WHATSAPP, VehiclePayloadCodec.whatsapp(from, message, false))

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            var uptime = 0L
            while (isActive) {
                delay(30_000)
                uptime += 30
                gattClient?.sendPayload(BleVehicleProfile.PAYLOAD_VC_UPTIME, VehiclePayloadCodec.vcUptime(uptime))
            }
        }
    }

    // ---------------------------------------------------------------- call feed (t=1)

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun registerCallFeed() {
        val tm = telephony ?: return
        if (!hasCallPermission()) { log("call feed idle: READ_PHONE_STATE not granted"); return }
        if (callStateFeed.get() != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = pushCallState(state)
            }
            callStateFeed.set(callback)
            runCatching { tm.registerTelephonyCallback(context.mainExecutor, callback) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = pushCallState(state)
            }
            callStateFeed.set(listener)
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
        }
        update { it.copy(callFeedActive = true) }
        log("call feed active")
    }

    private fun unregisterCallFeed() {
        val feed = callStateFeed.getAndSet(null) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) telephony?.unregisterTelephonyCallback(feed as TelephonyCallback)
            else @Suppress("DEPRECATION") telephony?.listen(feed as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        update { it.copy(callFeedActive = false) }
    }

    private fun pushCallState(state: Int) {
        val label = when (state) {
            android.telephony.TelephonyManager.CALL_STATE_RINGING -> "ringing"
            android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        log("call state: $label")
        gattClient?.sendPayload(
            BleVehicleProfile.PAYLOAD_PHONE_CALL,
            VehiclePayloadCodec.phoneCall("", state, false), priority = true
        )
    }

    /** Called by the foreground service when it starts (boot / restore path). */
    fun onServiceStarted() {
        if (_state.value.enabled && _state.value.link == LinkStatus.OFF && adapter?.isEnabled == true) {
            startInternal(autoReconnect = true)
        }
    }
}
