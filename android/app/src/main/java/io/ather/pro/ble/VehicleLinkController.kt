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
import android.location.LocationListener
import android.location.LocationManager
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
    private var gattServer: VehicleGattServerManager? = null
    private var keepaliveJob: Job? = null
    private var mediaPollJob: Job? = null
    private var rescanJob: Job? = null
    @Volatile private var lastFixLat = 0.0
    @Volatile private var lastFixLng = 0.0
    @Volatile private var lastFixAt = 0L
    private var route: TbtRouter.Route? = null
    private var routeStep = 0
    private var offRouteCount = 0
    @Volatile private var lastRouteAt = 0L
    @Volatile private var currentPhoneNumber = ""
    /** Latest dialer caller name captured by the notification listener. */
    @Volatile private var currentCallerName = ""
    @Volatile private var lastTelephonyState = -1
    @Volatile private var lastPushedCallName: String? = null
    /** Shared-destination tracking (map share -> dash navigation updates). */
    private var destination: SharedLocationParser.Place? = null
    private var destinationJob: Job? = null

    /** Set when a new token is registered: next start advertises the PLAINTEXT
     *  token for 180 s (Ather freshConnection=true) so the dash — which scans
     *  during its pairing screen — can find this phone. */
    @Volatile private var pendingFreshPairing = false
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
        VehicleBeaconAdvertiser.logger = { log(it) }
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
        // DashboardBTClient.setUserNameFromHMI: strip all whitespace; the dash name is
        // the token truncated/underscore-padded to (16 - scooterName.length) bytes.
        val (_, padded) = BleVehicleProfile.normalizeToken(token)
        log("pairing token registered: \"$token\" (dash broadcasts name: \"$padded\")")
        pendingFreshPairing = true
        renamePhoneBluetooth(token)
        if (_state.value.enabled) restart()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(BleVehicleProfile.PREF_LINK_ENABLED, on).apply()
        update { it.copy(enabled = on) }
        if (on) startInternal(autoReconnect = true) else stopInternal()
    }

    fun restart(freshPairing: Boolean? = null) {
        if (freshPairing != null) pendingFreshPairing = freshPairing
        stopInternal()
        if (_state.value.enabled) startInternal(autoReconnect = true)
    }

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

        // BluetoothClient.setDeviceName: the dash recognises the owner's phone by its
        // Bluetooth name "<token>'s <scooterName>" — set it before anything else.
        renamePhoneBluetooth(_state.value.token)

        // BLEServerService role: the dash is the GATT CLIENT ("Pair phone to control
        // Calls, Music…") — the phone must host the profile server for it to connect to.
        if (!ensureServer()) log("could not open GATT server — the dash will not be able to connect")

        // No eager phone→dash client connect: a pending outgoing GATT connection can
        // wedge the stack while the dash is trying to connect IN as client (the real
        // app only client-connects after seeing the dash's own advertisement).
        val freshPairing = pendingFreshPairing
        pendingFreshPairing = false

        startBeaconScan()

        // The dash sits on its pairing screen SCANNING for a phone that advertises the
        // token. Fresh pairing = plaintext token for 180 s (advertiseBeacon fresh=true);
        // reconnect = AES-truncated token, un-timed.
        advertiser?.let {
            if (freshPairing) {
                VehicleBeaconAdvertiser.startPlainToken(it, _state.value.token)
                log("requesting pairing beacon: \"$(_state.value.token)\" plaintext for 180 s — keep the dash on its QR screen")
            } else {
                val enc = VehicleBeaconAdvertiser.startEncryptedToken(it, _state.value.token)
                log("reconnect beacon on (AES payload: $enc)")
            }
        } ?: log("no LE advertiser on this device — the dash cannot find this phone; use 'Scan for devices' instead")

        // Ather's BeaconManager re-arms the scan periodically (3 s stop → restart); the
        // dash may start advertising long after the phone began scanning.
        rescanJob?.cancel()
        rescanJob = scope.launch {
            while (isActive && _state.value.link == LinkStatus.SCANNING) {
                delay(10_000)
                if (_state.value.link != LinkStatus.SCANNING) break
                val scan = leScanner ?: break
                log("restarting beacon scan")
                scanner?.stop(leScanner)
                runCatching { scanner?.start(scan, _state.value.token) }
                    .onFailure { log("scan restart failed: ${it.message}") }
            }
        }

        if (!WhatsAppNotificationListener.hasAccess(context)) {
            log("real WhatsApp forwarding is OFF — grant Notification access: Settings → Notifications → App settings → AtherMate → Notification access")
        }

        io.ather.pro.service.VehicleLinkService.start(context)
    }

    private fun startBeaconScan() {
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
    }

    /** BluetoothClient.setDeviceName: adapter.setName("<token>'s <scooter>", ≤19 bytes. */
    private fun renamePhoneBluetooth(token: String) {
        val a = adapter ?: return
        val desired = BleVehicleProfile.phoneBtName(token)
        runCatching {
            val current = a.name
            if (current != desired && desired.isNotBlank()) {
                a.setName(desired)
                log("phone Bluetooth name set to \"$desired\" (was \"$current\")")
            }
        }
    }

    private fun stopInternal() {
        rescanJob?.cancel(); rescanJob = null
        scanner?.stop(leScanner); scanner = null
        pairing?.unregister(); pairing = null
        gattClient?.disconnect(); gattClient = null
        gattServer?.stop(); gattServer = null
        VehicleBeaconAdvertiser.stop(advertiser)
        keepaliveJob?.cancel(); keepaliveJob = null
        mediaPollJob?.cancel(); mediaPollJob = null
        destinationJob?.cancel(); destinationJob = null
        unregisterCallFeed()
        io.ather.pro.service.VehicleLinkService.stop(context)
        setLink(LinkStatus.OFF, null)
        update { it.copy(ignitionOn = false) }
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
                val scan = leScanner
                if (scan != null) runCatching { scanner?.start(scan, _state.value.token) }
            }
        ).also { pairing = it }
    }

    /**
     * The dash is the GATT client (Ather: BLEServerService + BluetoothServer). Host
     * the profile server whenever the link is enabled so the bonded dash can connect
     * in whenever it wants — ignition on, riding, etc.
     */
    private fun ensureServer(): Boolean {
        if (gattServer != null) return true
        val server = VehicleGattServerManager(
            context, scope,
            onConnectionChanged = { device, connected ->
                if (connected) {
                    device?.let { rememberLastConnected(it) }
                    setLink(LinkStatus.CONNECTED, device)
                    // The real app starts its payload machinery the moment the dash
                    // connects (BLEServerService initServer callback) — keepalive,
                    // media poll, and an immediate now-playing push.
                    startKeepalive()
                    scope.launch { runCatching { MediaBridge.pollAndPush(context) } }
                    // Ather sends the t=0 connection payload right after the dash
                    // subscribes; queue ours immediately (server drops it until then).
                    serverSendPayload(
                        BleVehicleProfile.PAYLOAD_CONNECTION,
                        VehiclePayloadCodec.connection(
                            btName = Build.MODEL,
                            model = Build.MANUFACTURER,
                            os = "Android ${Build.VERSION.RELEASE}",
                            libVersion = "athermate/1.0"
                        )
                    )
                    registerCallFeed()
                } else {
                    log("dash disconnected from phone server (status-8 = link timeout)")
                    if (_state.value.link == LinkStatus.CONNECTED) setLink(LinkStatus.OFF, null)
                    reArmBeacon()
                }
            },
            onCommand = { cmd, _ -> handleCommand(cmd) },
            onLog = { log(it) }
        )
        val ok = server.start()
        gattServer = server
        return ok
    }

    /** Push a payload over whichever role is live (server beats client). */
    private fun serverSendPayload(type: Int, bytes: ByteArray) {
        gattServer?.sendPayload(type, bytes)
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
                        mediaPollJob?.cancel(); mediaPollJob = null
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
            BleVehicleProfile.CMD_MEDIA_PREVIOUS -> { MediaBridge.sendCommand(context, cmd); "MEDIA_PREVIOUS" }
            BleVehicleProfile.CMD_MEDIA_TOGGLE -> { MediaBridge.sendCommand(context, cmd); "MEDIA_TOGGLE" }
            BleVehicleProfile.CMD_MEDIA_NEXT -> { MediaBridge.sendCommand(context, cmd); "MEDIA_NEXT" }
            BleVehicleProfile.CMD_VOLUME_UP -> { MediaBridge.sendCommand(context, cmd); "VOLUME_UP" }
            BleVehicleProfile.CMD_VOLUME_DOWN -> { MediaBridge.sendCommand(context, cmd); "VOLUME_DOWN" }
            BleVehicleProfile.CMD_CALL_ACCEPT -> { answerCallFromDash(); "CALL_ACCEPT" }
            BleVehicleProfile.CMD_CALL_REJECT -> { rejectCallFromDash(); "CALL_REJECT" }
            BleVehicleProfile.CMD_CALL_REJECT_WITH_MESSAGE -> { rejectCallFromDash(); sendRejectSms(); "CALL_REJECT_WITH_SMS" }
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
        pushBoth(BleVehicleProfile.PAYLOAD_PHONE_CALL, VehiclePayloadCodec.phoneCall(name, if (ringing) 1 else 0, false), priority = true)

    fun sendMedia(track: String, artist: String, playing: Boolean, elapsedSec: Int, trackTimeSec: Int) =
        pushBoth(BleVehicleProfile.PAYLOAD_MEDIA,
            VehiclePayloadCodec.media(track, artist, if (playing) "playing" else "paused", elapsedSec, trackTimeSec))

    fun sendMedia(track: String, artist: String) =
        pushBoth(BleVehicleProfile.PAYLOAD_MEDIA, VehiclePayloadCodec.media(track, artist, "playing", 0, 0))

    fun sendTbt(instruction: String, maneuver: Int, meters: Int) =
        pushBoth(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(maneuver, instruction, meters))

    fun sendWhatsapp(from: String, message: String, group: Boolean = false) =
        pushBoth(BleVehicleProfile.PAYLOAD_WHATSAPP, VehiclePayloadCodec.whatsapp(from, message, group))

    /** Send over the live role(s): server (dash-connected) and/or client. */
    private fun pushBoth(type: Int, bytes: ByteArray, priority: Boolean = false) {
        serverSendPayload(type, bytes)
        gattClient?.sendPayload(type, bytes, priority = priority)
    }

    /** Dialer call notification posted a caller name (real CallManager.parseData):
     *  digits are resolved through ContactsContract, then t=1 is RE-PUSHED — the
     *  telephony event fires before the notification, so the first ringing payload
     *  carries an empty name and the dash only learns who is calling here. */
    fun onCallerNameCaptured(name: String) {
        if (name.isBlank()) return
        val resolved = if (name.all { it.isDigit() } && name.length >= 10) {
            contactName(name) ?: name
        } else name
        if (resolved == currentCallerName) return
        currentCallerName = resolved
        if (name.all { it.isDigit() }) currentPhoneNumber = name
        log("caller identified: \"$resolved\"")
        // Re-send the live state with the name, like parseData -> callStateHandler.
        val st = lastTelephonyState
        if (st == android.telephony.TelephonyManager.CALL_STATE_RINGING ||
            st == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) {
            pushBoth(BleVehicleProfile.PAYLOAD_PHONE_CALL,
                VehiclePayloadCodec.phoneCall(resolved, st, false), priority = true)
        }
    }

    /** Real CallManager.getCallerName: last-10-digits PhoneLookup for the display name. */
    private fun contactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return null
        val digits = if (number.length > 10) number.takeLast(10) else number
        return runCatching {
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon().appendPath(digits).build()
            context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** WhatsApp call state from the notification stream (t=1, whatsapp=true). */
    fun onWhatsAppCall(callerName: String, state: Int) {
        log("WhatsApp call \"$callerName\" " + if (state == 1) "ringing" else "active")
        pushBoth(BleVehicleProfile.PAYLOAD_PHONE_CALL,
            VehiclePayloadCodec.phoneCall(callerName, state, true), priority = true)
    }

    fun onWhatsAppCallEnded() {
        log("WhatsApp call ended")
        pushBoth(BleVehicleProfile.PAYLOAD_PHONE_CALL,
            VehiclePayloadCodec.phoneCall("", 0, true), priority = true)
    }

    // ------------------------------------------------ call control from the dash

    /** Dash button: answer the live call (TelecomManager, or WA notification action). */
    private fun answerCallFromDash() {
        // WhatsApp call first: fire the notification's own Answer action.
        WhatsAppNotificationListener.waCallActions?.let { actions ->
            actions.firstOrNull { a -> a.title?.toString()?.lowercase()?.let { it.contains("answer") || it.contains("accept") } == true }
                ?.let { action -> runCatching { action.actionIntent?.send() }; log("WhatsApp call answered from dash"); return }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecom != null && ContextCompat.checkSelfPermission(context, android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                runCatching { telecom.acceptRingingCall() }
                log("call answered from dash")
                return
            }
        }
        log("cannot answer: no ANSWER_PHONE_CALLS permission")
    }

    /** Dash button: reject/hang up (TelecomManager.endCall, or WA Decline action). */
    private fun rejectCallFromDash() {
        WhatsAppNotificationListener.waCallActions?.let { actions ->
            actions.firstOrNull { a -> a.title?.toString()?.lowercase()?.let { it.contains("decline") || it.contains("reject") || it.contains("hang") } == true }
                ?.let { action -> runCatching { action.actionIntent?.send() }; log("WhatsApp call declined from dash"); return }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecom != null && ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val ended = runCatching { telecom.endCall() }.getOrDefault(false)
                log(if (ended) "call ended from dash" else "endCall returned false")
                return
            }
        }
        log("cannot end call: READ_PHONE_STATE missing")
    }

    /** Byte 8: original endCallAndSendMessage — reject plus an auto-SMS to the
     *  incoming number when the user enabled it (real app sends to phoneNumber). */
    private fun sendRejectSms() {
        val prefs = context.getSharedPreferences(BleVehicleProfile.PREFS_FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(BleVehicleProfile.PREF_SMS_ON_REJECT, false)) return
        val number = currentPhoneNumber
        if (number.isBlank()) { log("auto-SMS skipped: incoming number unknown"); return }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            log("auto-SMS skipped: SEND_SMS not granted")
            return
        }
        val text = prefs.getString(BleVehicleProfile.PREF_SMS_TEMPLATE, BleVehicleProfile.DEFAULT_SMS_TEMPLATE)
            ?: BleVehicleProfile.DEFAULT_SMS_TEMPLATE
        runCatching {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                context.getSystemService(android.telephony.SmsManager::class.java)
            else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            sm?.sendTextMessage(number, null, text, null, null)
            log("auto-SMS sent to $number")
        }.onFailure { log("auto-SMS failed: ${it.message}") }
    }

    // ------------------------------------------------ shared destination (map share -> dash)

    /** A location shared from Google Maps (ACTION_SEND text share or geo:) — toast the
     *  dash, then stream REAL turn-by-turn maneuvers (OSRM route) as t=6, falling
     *  back to straight-line guidance when no route is available. */
    fun setDestination(place: SharedLocationParser.Place) {
        destination = place
        destinationJob?.cancel()
        route = null
        routeStep = 0
        log("destination set: $place")
        serverSendPayload(BleVehicleProfile.PAYLOAD_TOAST,
            VehiclePayloadCodec.toast("Navigating to " + (place.label ?: "shared point")))
        pushBoth(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(
            BleVehicleProfile.TBT_LOCATION_STARTED, "To " + (place.label ?: "destination"), 0))
        startDestinationUpdates()
    }

    fun clearDestination() {
        destination = null
        route = null
        destinationJob?.cancel(); destinationJob = null
        log("destination cleared")
    }

    @SuppressLint("MissingPermission")
    private fun startDestinationUpdates() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            log("location permission not granted — destination updates off")
            return
        }
        destinationJob = scope.launch {
            val listener = LocationListener { loc ->
                lastFixLat = loc.latitude; lastFixLng = loc.longitude
                lastFixAt = System.currentTimeMillis()
                onRidingFix()
            }
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 15f, listener, context.mainLooper) }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 20_000L, 50f, listener, context.mainLooper) }
            try {
                while (isActive && destination != null) delay(2_000)
            } finally {
                runCatching { lm.removeUpdates(listener) }
            }
        }
    }

    /** Called on every GPS fix: fetch/advance the route and push the next maneuver. */
    private fun onRidingFix() {
        val dest = destination ?: return
        if (System.currentTimeMillis() - lastFixAt > 20_000) return // stale fix
        val destDist = haversineMeters(lastFixLat, lastFixLng, dest.lat, dest.lng)
        if (destDist < 40) {
            serverSendPayload(BleVehicleProfile.PAYLOAD_TOAST,
                VehiclePayloadCodec.toast("Arrived: " + (dest.label ?: "destination")))
            pushBoth(BleVehicleProfile.PAYLOAD_TBT,
                VehiclePayloadCodec.tbt(BleVehicleProfile.TBT_DEST_REACHED, "Destination", 0))
            destination = null; route = null
            destinationJob?.cancel()
            log("destination reached")
            return
        }
        scope.launch {
            val needFetch = route == null ||
                System.currentTimeMillis() - lastRouteAt > 5 * 60_000 || offRouteCount >= 2
            if (needFetch) {
                val r = TbtRouter.fetch(lastFixLat, lastFixLng, dest.lat, dest.lng)
                if (r != null) {
                    if (route != null) {
                        pushBoth(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(
                            BleVehicleProfile.TBT_IND_REROUTE, "Re-routing", 0))
                    }
                    route = r; routeStep = 0; offRouteCount = 0
                    lastRouteAt = System.currentTimeMillis()
                    log("route: ${r.steps.size} steps, ${formatDistance(r.totalMeters)} total")
                } else {
                    log("route fetch failed — straight-line guidance")
                }
            }
            val r = route
            if (r == null || r.steps.isEmpty()) {
                updateFallback(dest)
                return@launch
            }
            if (routeStep >= r.steps.size) routeStep = r.steps.size - 1
            val step = r.steps[routeStep]
            val dToManeuver = haversineMeters(lastFixLat, lastFixLng, step.lat, step.lng)
            // Off-route heuristic: far past the maneuver point for consecutive fixes.
            if (dToManeuver > maxOf(200.0, step.distanceMeters * 2.0)) offRouteCount++ else offRouteCount = 0
            if (dToManeuver < 40 && routeStep < r.steps.size - 1) {
                routeStep++
                val next = r.steps[routeStep]
                pushBoth(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(
                    next.maneuverId, next.instruction, next.distanceMeters.toInt()))
                return@launch
            }
            pushBoth(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(
                step.maneuverId, step.instruction, dToManeuver.toInt()))
        }
    }

    /** Straight-line guidance when no route is available (offline, OSRM down). */
    private fun updateFallback(dest: SharedLocationParser.Place) {
        val dist = haversineMeters(lastFixLat, lastFixLng, dest.lat, dest.lng)
        val bearing = bearingDegrees(lastFixLat, lastFixLng, dest.lat, dest.lng)
        pushBoth(BleVehicleProfile.PAYLOAD_TBT, VehiclePayloadCodec.tbt(
            BleVehicleProfile.TBT_CONTINUE_STRAIGHT,
            (dest.label ?: "Destination") + " · " + formatDistance(dist) + " " + compass8(bearing),
            dist.toInt()))
    }

    private fun haversineMeters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val a = Math.sin(dLa / 2) * Math.sin(dLa / 2) +
            Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    private fun bearingDegrees(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val la1r = Math.toRadians(la1); val la2r = Math.toRadians(la2)
        val dLo = Math.toRadians(lo2 - lo1)
        val y = Math.sin(dLo) * Math.cos(la2r)
        val x = Math.cos(la1r) * Math.sin(la2r) - Math.sin(la1r) * Math.cos(la2r) * Math.cos(dLo)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    private fun formatDistance(m: Double): String =
        if (m < 950) "" + m.toInt() + " m"
        else String.format(java.util.Locale.US, "%.1f km", m / 1000)

    private fun compass8(deg: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(Math.round(deg / 45) % 8).toInt()]

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            var uptime = 0L
            while (isActive) {
                delay(30_000)
                uptime += 30
                pushBoth(BleVehicleProfile.PAYLOAD_VC_UPTIME, VehiclePayloadCodec.vcUptime(uptime))
            }
        }
        // Media polling: pushes now-playing for ANY player with a MediaSession,
        // even ones that post no MediaStyle notification (e.g. Bitchord).
        mediaPollJob?.cancel()
        mediaPollJob = scope.launch {
            while (isActive) {
                delay(5_000)
                runCatching { MediaBridge.pollAndPush(context) }
            }
        }
    }

    /**
     * Legacy connectable advertising stops automatically once the dash connects;
     * when it later disconnects the beacon is gone and the dash can never find the
     * phone again. Re-arm it (and use the keepalive loop as a watchdog).
     */
    private fun reArmBeacon() {
        val adv = advertiser ?: return
        if (_state.value.link == LinkStatus.CONNECTED) return
        scope.launch {
            delay(2_000)
            if (_state.value.link != LinkStatus.CONNECTED) {
                runCatching { VehicleBeaconAdvertiser.startEncryptedToken(adv, _state.value.token) }
                log("beacon re-armed after disconnect")
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
        pushBoth(BleVehicleProfile.PAYLOAD_PHONE_CALL, VehiclePayloadCodec.phoneCall(currentCallerName, state, false), priority = true)
    }

    /** Called by the foreground service when it starts (boot / restore path). */
    fun onServiceStarted() {
        if (_state.value.enabled && _state.value.link == LinkStatus.OFF && adapter?.isEnabled == true) {
            startInternal(autoReconnect = true)
        }
    }
}
