package io.ather.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ather.pro.data.auth.AuthExpiredException
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.data.auth.AuthStep
import io.ather.pro.data.auth.AuthUiState
import io.ather.pro.data.auth.AtherAuthApi
import io.ather.pro.data.auth.DiscoveredScooter
import io.ather.pro.data.auth.JwtExpiry
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val sessionStore: SecureSessionStore,
    private val authApi: AtherAuthApi,
    private val repository: AtherRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { session ->
                if (session?.isComplete == true) {
                    repository.applyCredentials(session.token, session.vehicleUuid)
                    _ui.update {
                        it.copy(
                            step = AuthStep.READY,
                            session = session,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                } else if (session == null && _ui.value.step == AuthStep.READY) {
                    repository.clearCredentials()
                    _ui.update {
                        AuthUiState(step = AuthStep.PHONE, phone = it.phone)
                    }
                }
            }
        }
    }

    fun onPhoneChanged(value: String) {
        _ui.update { it.copy(phone = value.filter { ch -> ch.isDigit() }.take(15), errorMessage = null) }
    }

    fun onOtpChanged(value: String) {
        _ui.update { it.copy(otp = value.filter { ch -> ch.isDigit() }.take(8), errorMessage = null) }
    }

    fun requestOtp() {
        val phone = _ui.value.phone.trim()
        if (phone.length < 10) {
            _ui.update { it.copy(errorMessage = "Enter a valid mobile number") }
            return
        }
        _ui.update { it.copy(isLoading = true, errorMessage = null) }
        authApi.requestOtp(phone, _ui.value.countryCode) { result ->
            _ui.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(step = AuthStep.OTP, isLoading = false, errorMessage = null)
                    },
                    onFailure = { error ->
                        state.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Could not send OTP"
                        )
                    }
                )
            }
        }
    }

    fun verifyOtp() {
        val state = _ui.value
        val phone = state.phone.trim()
        val otp = state.otp.trim()
        if (otp.length < 4) {
            _ui.update { it.copy(errorMessage = "Enter the OTP from SMS") }
            return
        }
        _ui.update { it.copy(isLoading = true, errorMessage = null) }
        authApi.verifyOtp(phone, otp, state.countryCode) { result ->
            result.fold(
                onSuccess = { token ->
                    discoverScooters(token, phone)
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "OTP verification failed"
                        )
                    }
                }
            )
        }
    }

    fun selectScooter(scooter: DiscoveredScooter) {
        val token = _ui.value.pendingToken ?: return
        completeSession(token, scooter, _ui.value.phone)
    }

    fun retryScooters() {
        val token = _ui.value.pendingToken ?: return
        _ui.update { it.copy(isLoading = true, errorMessage = null) }
        discoverScooters(token, _ui.value.phone)
    }

    fun backToPhone() {
        _ui.update {
            it.copy(
                step = AuthStep.PHONE,
                otp = "",
                pendingToken = null,
                scooters = emptyList(),
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun logout() {
        repository.clearCredentials()
        sessionStore.clear()
        _ui.value = AuthUiState()
    }

    private fun discoverScooters(token: String, phone: String) {
        authApi.fetchScooters(token) { result ->
            result.fold(
                onSuccess = { scooters ->
                    if (scooters.size == 1) {
                        completeSession(token, scooters.first(), phone)
                    } else {
                        _ui.update {
                            it.copy(
                                step = AuthStep.SCOOTER_SELECT,
                                pendingToken = token,
                                scooters = scooters,
                                isLoading = false,
                                errorMessage = if (scooters.isEmpty()) {
                                    "No scooters linked on this Ather account yet."
                                } else {
                                    null
                                }
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (error is AuthExpiredException) {
                        sessionStore.clear()
                        _ui.update {
                            AuthUiState(
                                step = AuthStep.PHONE,
                                phone = phone,
                                errorMessage = error.message
                            )
                        }
                    } else {
                        _ui.update {
                            it.copy(
                                step = AuthStep.SCOOTER_SELECT,
                                pendingToken = token,
                                scooters = emptyList(),
                                isLoading = false,
                                errorMessage = error.message
                                    ?: "Could not load scooters. Tap Retry."
                            )
                        }
                    }
                }
            )
        }
    }

    private fun completeSession(token: String, scooter: DiscoveredScooter, phone: String) {
        val session = AuthSession(
            token = token,
            vehicleUuid = scooter.uuid,
            phone = phone,
            displayName = scooter.displayName,
            expiresAtEpochSec = JwtExpiry.expiresAtEpochSec(token)
        )
        sessionStore.save(session)
        repository.applyCredentials(session.token, session.vehicleUuid)
        _ui.update {
            it.copy(
                step = AuthStep.READY,
                session = session,
                isLoading = false,
                errorMessage = null,
                pendingToken = null
            )
        }
    }

    private fun initialState(): AuthUiState {
        val session = sessionStore.current()
        return if (session?.isComplete == true) {
            AuthUiState(step = AuthStep.READY, session = session, phone = session.phone.orEmpty())
        } else {
            AuthUiState()
        }
    }

    class Factory(
        private val sessionStore: SecureSessionStore,
        private val authApi: AtherAuthApi,
        private val repository: AtherRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                return AuthViewModel(sessionStore, authApi, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
