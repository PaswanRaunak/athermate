package io.ather.pro.data.repository

import android.content.Context
import io.ather.pro.data.api.AtherApiClient
import io.ather.pro.data.charging.ChargeLimitStore
import io.ather.pro.data.local.DashboardLocalStore
import io.ather.pro.data.local.TripBaseline
import io.ather.pro.domain.charging.ChargingEvidence
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.battery.BatteryHistory
import io.ather.pro.domain.battery.RideHistory
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.charging.RemoteChargingDispatcher
import io.ather.pro.domain.charging.RemoteChargingGateway
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterSettings
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.domain.repository.ScooterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import okhttp3.WebSocket
import java.util.UUID
import kotlin.math.roundToInt

class AtherRepository(
    context: Context? = null,
    remoteChargingGateway: RemoteChargingGateway? = null
) : ScooterRepository {
    private val appContext = context?.applicationContext
    private val localStore: DashboardLocalStore? by lazy { appContext?.let(::DashboardLocalStore) }
    private val preferences = appContext?.getSharedPreferences("ather_dashboard_settings", Context.MODE_PRIVATE)
    private val chargeLimitStore: ChargeLimitStore? = appContext?.let(::ChargeLimitStore)
    private val api = AtherApiClient()
    private val chargingDispatcher = RemoteChargingDispatcher(
        gateway = remoteChargingGateway ?: api.asRemoteChargingGateway()
    )
    // One application-owned worker serializes telemetry, persistence and command decisions.
    private val scope = CoroutineScope(SupervisorJob() +
        Executors.newSingleThreadExecutor { task -> Thread(task, "ather-repository") }.asCoroutineDispatcher())
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var commandTimeoutJob: Job? = null
    private var manuallyDisconnected = false

    // Persisted sync-to-sync trip baseline. Odometer and SoC often arrive in separate
    // WebSocket deltas, so this baseline must not be reset by either field alone.
    private var lastSavedOdo: Double? = null
    private var lastSavedSoc: Double? = null
    private var lastSavedTimestamp: Long = System.currentTimeMillis()
    private var lastHistoryPersistAt = 0L
    private var evidence = ChargingEvidence()
    @Volatile private var generation = 0L
    private var reconnectAttempt = 0
    private var lastChargeRefreshAt = 0L

    private val _dashboard = MutableStateFlow(ScooterDashboardState(
        settings = ScooterSettings(
            selectedModel = runCatching { ScooterModel.valueOf(preferences?.getString("model", null).orEmpty()) }
                .getOrDefault(ScooterModel.ATHER_450X_3_7),
            tariffRatePerKWh = preferences?.getFloat("tariff", 8f)?.toDouble() ?: 8.0
        ),
        connection = ConnectionStatus.DISCONNECTED
    ))
    private val storageReady = scope.async {
        runCatching {
        val restored = localStore?.loadTripBaseline()
        lastSavedOdo = restored?.odometerKm
        lastSavedSoc = restored?.batterySoc
        lastSavedTimestamp = restored?.timestampMs ?: System.currentTimeMillis()
        val trips = localStore?.loadTrips().orEmpty()
        val history = localStore?.loadTelemetryHistory().orEmpty()
        val rides = localStore?.loadRideHistory().orEmpty()
        _dashboard.update { it.copy(recentTrips = trips, telemetryHistory = history, rideHistory = rides) }
        }.onFailure {
            _dashboard.update { it.copy(errorMessage = "Saved history is unavailable. Live scooter data can still connect.") }
        }
        Unit
    }
    override val dashboard: StateFlow<ScooterDashboardState> = _dashboard.asStateFlow()

    private val _chargeLimit = MutableStateFlow(ChargeLimitController.Snapshot())
    override val chargeLimit: StateFlow<ChargeLimitController.Snapshot> = _chargeLimit.asStateFlow()

    @Volatile
    private var authToken: String? = null

    @Volatile
    private var vehicleUuid: String? = null

    @Volatile
    private var authInvalidated = false

    private val _authenticationRequired = MutableStateFlow<String?>(null)
    val authenticationRequired: StateFlow<String?> = _authenticationRequired.asStateFlow()

    init {
        INSTANCE = this
        commandTimeoutJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                tickRemoteChargingTimeouts()
                if (hasCredentials()) {
                    val now = System.currentTimeMillis()
                    processChargeLimit(_dashboard.value.telemetry, now)
                    if (_chargeLimit.value.enabled && !manuallyDisconnected && now - lastChargeRefreshAt >= 20_000L) {
                        lastChargeRefreshAt = now
                        socket?.let(api::subscribe)
                        // A silent or wedged stream must obtain a new subscription snapshot.
                        val latestCharge = evidence.chargingAt ?: 0L
                        if (_dashboard.value.connection == ConnectionStatus.CONNECTED && now - latestCharge > 120_000L) connect()
                    }
                }
            }
        }
    }

    @Synchronized
    fun applyCredentials(token: String, uuid: String) {
        if (token.isBlank() || uuid.isBlank()) return
        val changed = authToken != token || vehicleUuid != uuid
        authToken = token
        vehicleUuid = uuid
        authInvalidated = false
        _authenticationRequired.value = null
        if (changed) {
            generation += 1
            evidence = ChargingEvidence()
            loadChargeLimitForVehicle(uuid)
        }
        if (changed || socket == null) {
            loadVehicleProfileAndRides()
            connect()
        }
    }

    @Synchronized
    fun clearCredentials() {
        generation += 1
        evidence = ChargingEvidence()
        authToken = null
        vehicleUuid = null
        manuallyDisconnected = true
        reconnectJob?.cancel()
        socket?.close(1000, "Signed out")
        socket = null
        _chargeLimit.value = ChargeLimitController.Snapshot()
        _dashboard.update {
            it.copy(
                connection = ConnectionStatus.DISCONNECTED,
                errorMessage = null,
                telemetry = null,
                lastUpdated = null, gpsUpdatedAt = null, batteryUpdatedAt = null, chargingUpdatedAt = null,
                vehicleProfile = null,
                remoteChargingCommand = RemoteChargingCommand()
            )
        }
    }

    fun hasCredentials(): Boolean = !authToken.isNullOrBlank() && !vehicleUuid.isNullOrBlank()

    private fun requireCredentials(): Pair<String, String>? {
        val token = authToken
        val uuid = vehicleUuid
        if (token.isNullOrBlank() || uuid.isNullOrBlank()) {
            _dashboard.update {
                it.copy(
                    connection = ConnectionStatus.DISCONNECTED,
                    errorMessage = "Sign in required"
                )
            }
            return null
        }
        return token to uuid
    }

    private fun handleAuthFailure(message: String) {
        if (authInvalidated) return
        authInvalidated = true
        generation += 1
        manuallyDisconnected = true
        reconnectJob?.cancel()
        socket?.close(1000, "Authentication expired")
        socket = null
        authToken = null
        vehicleUuid = null
        _dashboard.update {
            it.copy(
                connection = ConnectionStatus.ERROR,
                errorMessage = message.ifBlank { "Session expired. Please sign in again." }
            )
        }
        _authenticationRequired.value = message.ifBlank { "Your Ather session has expired." }
    }

    private fun isAuthFailureMessage(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("session expired") ||
            lower.contains("unauthorized") ||
            Regex("\\b401\\b").containsMatchIn(lower) ||
            lower.contains("not authorized")
    }

    private fun loadVehicleProfileAndRides() {
        val (token, uuid) = requireCredentials() ?: return
        api.fetchVehicleProfile(token, uuid) { result ->
            if (authToken != token || vehicleUuid != uuid) return@fetchVehicleProfile
            result.onSuccess { profile ->
                _dashboard.update { state ->
                    val apiModel = profile.resolvedModel
                    state.copy(
                        vehicleProfile = profile,
                        settings = if (apiModel != null && preferences?.contains("model") != true) {
                            state.settings.copy(selectedModel = apiModel)
                        } else state.settings
                    )
                }
                profile.scooterId?.let(::loadOfficialRides)
            }.onFailure { error ->
                val message = error.message.orEmpty()
                if (isAuthFailureMessage(message)) {
                    handleAuthFailure(message)
                } else {
                    _dashboard.update { state ->
                        state.copy(errorMessage = state.errorMessage ?: "Vehicle details unavailable: ${error.message}")
                    }
                }
            }
        }
    }

    private fun loadOfficialRides(scooterId: String) {
        val (token, _) = requireCredentials() ?: return
        val settings = _dashboard.value.settings
        api.fetchRides(
            token = token,
            scooterId = scooterId,
            usableCapacityWh = settings.selectedModel.usableCapacityWh,
            tariffRatePerKWh = settings.tariffRatePerKWh
        ) { result ->
            scope.launch {
                storageReady.await()
                if (authToken != token) return@launch
                result.onSuccess { officialRides ->
                    val localRides = _dashboard.value.recentTrips.filterNot { it.isOfficialRide }
                    val merged = (officialRides + localRides).distinctBy(TripRecord::id)
                        .sortedByDescending(TripRecord::startTimeMs).take(100)
                    _dashboard.update { it.copy(recentTrips = merged) }
                    persist { saveTrips(merged) }
                }.onFailure { error ->
                    if (isAuthFailureMessage(error.message.orEmpty())) handleAuthFailure(error.message.orEmpty())
                }
            }
        }
    }

    @Synchronized
    private fun connect() {
        val (token, uuid) = requireCredentials() ?: return
        manuallyDisconnected = false
        reconnectJob?.cancel()
        val connectionGeneration = ++generation
        socket?.cancel()
        _dashboard.update { it.copy(connection = ConnectionStatus.CONNECTING, errorMessage = null) }
        socket = api.connect(token, uuid, object : AtherApiClient.Listener {
            override fun onConnected(socket: WebSocket) {
                if (generation != connectionGeneration || manuallyDisconnected) { socket.cancel(); return }
                reconnectAttempt = 0
                this@AtherRepository.socket = socket
                _dashboard.update { it.copy(connection = ConnectionStatus.CONNECTED, errorMessage = null) }
                api.subscribe(socket)
            }

            override fun onTelemetry(telemetry: ScooterTelemetry) {
                scope.launch {
                    storageReady.await()
                    if (generation == connectionGeneration && !manuallyDisconnected) handleIncomingTelemetry(telemetry)
                }
            }

            override fun onDisconnected(reason: String) {
                if (generation != connectionGeneration) return
                socket = null
                if (!manuallyDisconnected) {
                    if (isAuthFailureMessage(reason)) {
                        handleAuthFailure(reason)
                    } else {
                        _dashboard.update { it.copy(connection = ConnectionStatus.DISCONNECTED, errorMessage = reason) }
                        scheduleReconnect()
                    }
                }
            }

            override fun onError(message: String) {
                if (generation != connectionGeneration || manuallyDisconnected) return
                if (isAuthFailureMessage(message)) {
                    handleAuthFailure(message)
                } else {
                    _dashboard.update { it.copy(connection = ConnectionStatus.ERROR, errorMessage = message) }
                }
            }
        })
    }

    private fun handleIncomingTelemetry(rawTelemetry: ScooterTelemetry) {
        val observedAt = System.currentTimeMillis()
        evidence = evidence.observe(rawTelemetry, observedAt)
        val existingTelemetry = _dashboard.value.telemetry
        val mergedTelemetry = existingTelemetry?.mergeWith(rawTelemetry) ?: rawTelemetry

        val currentSettings = _dashboard.value.settings
        val usableCapacityWh = currentSettings.selectedModel.usableCapacityWh

        // 1. Calculate Mode Efficiencies (Algorithm A)
        val soc = mergedTelemetry.batterySoc ?: 0.0
        val remainingEnergyWh = usableCapacityWh * (soc / 100.0)

        val enrichedModeRanges = mergedTelemetry.modeRanges.mapValues { (_, modeRange) ->
            val predKm = modeRange.predictedRangeKm ?: modeRange.rawRangeKm
            val derivedWhPerKm = if (predKm != null && predKm > 0.0 && remainingEnergyWh > 0.0) {
                remainingEnergyWh / predKm
            } else null
            val kmPerKWh = if (derivedWhPerKm != null && derivedWhPerKm > 0.0) {
                1000.0 / derivedWhPerKm
            } else null
            modeRange.copy(
                derivedWhPerKm = derivedWhPerKm,
                kmPerKWh = kmPerKWh
            )
        }

        val rideEff = enrichedModeRanges["Ride"]?.derivedWhPerKm
        val smartEcoEff = enrichedModeRanges["SmartEco"]?.derivedWhPerKm
        val avgWhPerKm = when {
            rideEff != null && rideEff > 0.0 -> rideEff
            smartEcoEff != null && smartEcoEff > 0.0 -> smartEcoEff
            else -> usableCapacityWh / currentSettings.selectedModel.referenceCycleRangeKm
        }

        // Ather's available properties/telemetry endpoints do not provide battery SoH.
        // Keep it null instead of presenting an age/cycle formula as a BMS measurement.
        val fullTelemetry = mergedTelemetry.copy(
            modeRanges = enrichedModeRanges,
            batteryHealth = null
        )

        // 3. Auto-detect Trips (Algorithm B)
        val previousOdo = lastSavedOdo
        val previousSoc = lastSavedSoc
        val now = System.currentTimeMillis()

        var updatedTrips = _dashboard.value.recentTrips

        val currentOdo = fullTelemetry.odoKm
        val currentBatterySoc = fullTelemetry.batterySoc
        if (previousOdo != null && previousSoc != null && currentOdo != null && currentBatterySoc != null) {
            val deltaOdo = currentOdo - previousOdo
            val deltaSoc = previousSoc - currentBatterySoc
            val vehicleState = fullTelemetry.vehicleState.orEmpty().trim().lowercase()
            val speedKmh = fullTelemetry.gps?.speed?.takeIf(Double::isFinite) ?: 0.0
            val isRiding = vehicleState.contains("rid") ||
                vehicleState.contains("mov") ||
                speedKmh > 1.0
            val isParked = vehicleState.contains("park") ||
                vehicleState.contains("sleep") ||
                vehicleState.contains("charg") ||
                vehicleState.contains("standby") ||
                vehicleState.contains("off")

            val baselineInvalid = deltaOdo < -0.05 ||
                deltaSoc < -0.5 ||
                fullTelemetry.charging == true

            if (baselineInvalid) {
                updateTripBaseline(currentOdo, currentBatterySoc, now)
            } else if (deltaOdo >= 0.15 && (isParked || (!isRiding && deltaSoc >= 0.1))) {
                val rangeEstimatedEnergyWh = deltaOdo * avgWhPerKm.coerceIn(8.0, 120.0)
                val socEstimatedEnergyWh = deltaSoc.coerceAtLeast(0.0) * (usableCapacityWh / 100.0)
                val socEfficiency = if (deltaOdo > 0.0) socEstimatedEnergyWh / deltaOdo else 0.0
                val energyWh = if (deltaSoc >= 0.25 && socEfficiency in 8.0..120.0) {
                    socEstimatedEnergyWh
                } else {
                    rangeEstimatedEnergyWh
                }
                val tripWhPerKm = energyWh / deltaOdo
                val tripCost = (energyWh / 1000.0) * currentSettings.tariffRatePerKWh
                val estimatedPackCapacityWh = if (deltaSoc >= 5.0) {
                    (rangeEstimatedEnergyWh / (deltaSoc / 100.0))
                        .coerceIn(usableCapacityWh * 0.50, usableCapacityWh * 1.20)
                } else {
                    null
                }

                val newTrip = TripRecord(
                    id = UUID.randomUUID().toString(),
                    startTimeMs = lastSavedTimestamp,
                    endTimeMs = now,
                    distanceKm = (deltaOdo * 100.0).roundToInt() / 100.0,
                    socConsumed = (deltaSoc.coerceAtLeast(0.0) * 10.0).roundToInt() / 10.0,
                    energyConsumedWh = (energyWh * 10.0).roundToInt() / 10.0,
                    efficiencyWhPerKm = (tripWhPerKm * 10.0).roundToInt() / 10.0,
                    electricityCostInr = (tripCost * 100.0).roundToInt() / 100.0,
                    startOdoKm = previousOdo,
                    endOdoKm = currentOdo,
                    estimatedPackCapacityWh = estimatedPackCapacityWh
                )

                updatedTrips = (listOf(newTrip) + updatedTrips).take(100)
                persist { saveTrips(updatedTrips) }
                updateTripBaseline(currentOdo, currentBatterySoc, now)
            }
        } else {
            if (currentOdo != null && currentBatterySoc != null) {
                updateTripBaseline(currentOdo, currentBatterySoc, now)
            }
        }

        val currentCount = _dashboard.value.packetCount + 1
        val updatedTimestamps = (_dashboard.value.recentPacketTimestamps + now).takeLast(60)

        val updatedHistory = BatteryHistory.record(
            history = _dashboard.value.telemetryHistory,
            report = rawTelemetry,
            observedAt = now
        )

        val rideHistory = RideHistory.record(_dashboard.value.rideHistory, rawTelemetry, now)
        if (now - lastHistoryPersistAt >= HISTORY_PERSIST_INTERVAL_MS) {
            persist { saveRideHistory(rideHistory) }
            persist { saveTelemetryHistory(updatedHistory) }
            lastHistoryPersistAt = now
        }

        _dashboard.update {
            val command = if (ChargingEvidence.hasChargeReading(rawTelemetry)) ChargingControl.advanceCommand(
                command = it.remoteChargingCommand,
                telemetry = fullTelemetry,
                nowMs = now
            ) else it.remoteChargingCommand
            it.copy(
                telemetry = fullTelemetry,
                lastUpdated = now,
                batteryUpdatedAt = evidence.batteryAt,
                chargingUpdatedAt = evidence.chargingAt,
                gpsUpdatedAt = if (rawTelemetry.gps?.latitude != null && rawTelemetry.gps.longitude != null) now else it.gpsUpdatedAt,
                recentTrips = updatedTrips,
                packetCount = currentCount,
                recentPacketTimestamps = updatedTimestamps,
                telemetryHistory = updatedHistory,
                rideHistory = rideHistory,
                remoteChargingCommand = command
            )
        }
        processChargeLimit(fullTelemetry, now)
    }

    private fun loadChargeLimitForVehicle(uuid: String) {
        _chargeLimit.value = chargeLimitStore?.load(uuid) ?: ChargeLimitController.Snapshot()
    }

    private fun updateChargeLimit(next: ChargeLimitController.Snapshot): Boolean {
        val uuid = vehicleUuid ?: return false
        val saved = chargeLimitStore?.save(uuid, next) ?: true
        _chargeLimit.value = if (saved) next else next.copy(status = ChargeLimitController.Status.ERROR,
            armed = false, message = "Could not save the charge limit. Free phone storage and retry.")
        return saved
    }

    override fun setChargeLimit(enabled: Boolean, percent: Int) {
        scope.launch {
            updateChargeLimit(ChargeLimitController.applySettings(_chargeLimit.value, enabled, percent))
            processChargeLimit(_dashboard.value.telemetry, System.currentTimeMillis())
        }
    }

    override fun retryChargeLimit() {
        scope.launch {
            updateChargeLimit(ChargeLimitController.retry(_chargeLimit.value))
            processChargeLimit(_dashboard.value.telemetry, System.currentTimeMillis())
        }
    }

    private fun processChargeLimit(telemetry: ScooterTelemetry?, nowMs: Long) {
        val decision = ChargeLimitController.onTelemetry(
            state = _chargeLimit.value,
            telemetry = telemetry,
            lastUpdatedMs = evidence.completeAt,
            nowMs = nowMs,
            chargingUpdatedMs = evidence.chargingAt
        )
        when (decision) {
            ChargeLimitController.Decision.None -> Unit
            is ChargeLimitController.Decision.StateOnly -> {
                updateChargeLimit(decision.next)
            }
            is ChargeLimitController.Decision.RequestStop -> {
                if (!updateChargeLimit(decision.next)) return
                val command = _dashboard.value.remoteChargingCommand
                val stopAlreadyPending =
                    (command.phase == RemoteCommandPhase.SENDING ||
                        command.phase == RemoteCommandPhase.ACCEPTED) &&
                        command.action.equals("stop", ignoreCase = true)
                if (stopAlreadyPending) {
                    // A manual stop is already in flight. Attach the limit state to
                    // its telemetry confirmation without sending a duplicate command.
                    return
                }
                val dispatched = sendRemoteCharging(start = false) { result ->
                    result.exceptionOrNull()?.let { error ->
                        val current = _chargeLimit.value
                        updateChargeLimit(
                            if (current.status != ChargeLimitController.Status.PENDING || current.pendingSinceMs != decision.next.pendingSinceMs) current
                            else current.copy(
                                status = ChargeLimitController.Status.ERROR,
                                message = error.message
                                    ?: "Ather rejected the automatic stop. Tap Retry limit.",
                                pendingSinceMs = null
                            )
                        )
                    }
                }
                if (!dispatched) {
                    val reason = _dashboard.value.remoteChargingCommand.message
                        ?: "Automatic stop could not be dispatched. Tap Retry limit."
                    updateChargeLimit(decision.next.copy(
                        status = ChargeLimitController.Status.ERROR,
                        message = reason,
                        pendingSinceMs = null
                    ))
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || manuallyDisconnected) return
        reconnectJob = scope.launch {
            val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(6)).coerceAtMost(60_000L)
            reconnectAttempt += 1
            delay(delayMs)
            if (isActive && !manuallyDisconnected) connect()
        }
    }

    override fun updateModel(model: ScooterModel) {
        _dashboard.update { state ->
            val updatedSettings = state.settings.copy(selectedModel = model)
            state.copy(settings = updatedSettings)
        }
        preferences?.edit()?.putString("model", model.name)?.apply()
    }

    override fun updateTariff(tariffRate: Double) {
        if (!tariffRate.isFinite() || tariffRate !in 0.0..100.0) return
        preferences?.edit()?.putFloat("tariff", tariffRate.toFloat())?.apply()
        _dashboard.update { state ->
            val updatedSettings = state.settings.copy(tariffRatePerKWh = tariffRate)
            state.copy(settings = updatedSettings)
        }
    }

    override fun clearTrips() {
        _dashboard.update { it.copy(recentTrips = emptyList()) }
        scope.launch { storageReady.await(); persist { saveTrips(emptyList()) } }
    }

    override fun refresh() {
        if (_dashboard.value.connection == ConnectionStatus.CONNECTING && socket != null) return
        loadVehicleProfileAndRides()
        val currentSocket = socket
        if (currentSocket != null && _dashboard.value.connection == ConnectionStatus.CONNECTED) {
            api.subscribe(currentSocket)
        } else {
            connect()
        }
    }

    @Synchronized
    override fun disconnect() {
        generation += 1
        manuallyDisconnected = true
        reconnectJob?.cancel()
        socket?.close(1000, "App closed")
        socket = null
        scope.launch {
            storageReady.await()
            persist { saveTelemetryHistory(_dashboard.value.telemetryHistory) }
            persist { saveRideHistory(_dashboard.value.rideHistory) }
        }
        _dashboard.update { it.copy(connection = ConnectionStatus.DISCONNECTED, errorMessage = null) }
    }

    @Synchronized
    private fun ensureConnected() {
        val status = _dashboard.value.connection
        if (status != ConnectionStatus.CONNECTING &&
            (socket == null || status != ConnectionStatus.CONNECTED)
        ) {
            connect()
        }
    }

    override fun pauseCharging(): Boolean = sendRemoteCharging(start = false)

    override fun resumeCharging(): Boolean = sendRemoteCharging(start = true)

    override fun clearRemoteChargingLatch() {
        _dashboard.update {
            it.copy(remoteChargingCommand = chargingDispatcher.clearLatch())
        }
    }

    override fun tickRemoteChargingTimeouts() {
        val state = _dashboard.value
        val telem = state.telemetry
        val current = state.remoteChargingCommand
        if (current.phase != RemoteCommandPhase.SENDING &&
            current.phase != RemoteCommandPhase.ACCEPTED &&
            !(current.phase == RemoteCommandPhase.CONFIRMED &&
                current.action.equals("stop", ignoreCase = true) &&
                ChargingControl.isActivelyCharging(telem))
        ) {
            return
        }
        val view = ChargingControl.resolveView(null, current)
        if (view.command != current) {
            _dashboard.update { it.copy(remoteChargingCommand = view.command) }
        }
    }

    @Synchronized
    private fun sendRemoteCharging(
        start: Boolean,
        onHttpResult: ((Result<Unit>) -> Unit)? = null
    ): Boolean {
        val token = authToken
        val uuid = vehicleUuid
        // The gateway callback can be synchronous in tests or unusually fast in
        // production. Do not apply it until SENDING has been committed to StateFlow.
        val stateCommitted = CompletableDeferred<Unit>()
        var expectedRequestedAt: Long? = null
        val attempt = chargingDispatcher.attempt(
            start = start,
            token = token,
            scooterUuid = uuid,
            telemetry = _dashboard.value.telemetry,
            current = _dashboard.value.remoteChargingCommand,
            supersedePending = true
        ) { result ->
            scope.launch {
                stateCommitted.await()
                if (authToken != token || vehicleUuid != uuid) return@launch
                val action = if (start) "start" else "stop"
                if (result.isFailure && isAuthFailureMessage(result.exceptionOrNull()?.message.orEmpty())) {
                    handleAuthFailure(result.exceptionOrNull()?.message.orEmpty())
                }
                _dashboard.update { state ->
                    state.copy(
                        remoteChargingCommand = chargingDispatcher.applyHttpResult(
                            current = state.remoteChargingCommand,
                            expectedAction = action,
                            result = result,
                            expectedRequestedAt = expectedRequestedAt
                        )
                    )
                }
                onHttpResult?.invoke(result)
            }
        }
        expectedRequestedAt = attempt.command.requestedAt
        _dashboard.update { it.copy(remoteChargingCommand = attempt.command) }
        stateCommitted.complete(Unit)
        return attempt.dispatched
    }

    companion object {
        @Volatile
        private var INSTANCE: AtherRepository? = null

        fun getInstance(context: Context? = null): AtherRepository {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: AtherRepository(context?.applicationContext).also { INSTANCE = it }
            }
        }

        private const val HISTORY_PERSIST_INTERVAL_MS = 15_000L
    }

    private fun persist(action: DashboardLocalStore.() -> Unit) {
        runCatching { localStore?.action() }.onFailure {
            _dashboard.update { it.copy(errorMessage = "Could not save history. Check available phone storage.") }
        }
    }

    private fun updateTripBaseline(odometerKm: Double, batterySoc: Double, timestampMs: Long) {
        lastSavedOdo = odometerKm
        lastSavedSoc = batterySoc
        lastSavedTimestamp = timestampMs
        persist { saveTripBaseline(TripBaseline(odometerKm, batterySoc, timestampMs)) }
    }
}
