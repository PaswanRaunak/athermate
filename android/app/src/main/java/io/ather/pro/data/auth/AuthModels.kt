package io.ather.pro.data.auth

data class AuthSession(
    val token: String,
    val vehicleUuid: String,
    val phone: String? = null,
    val displayName: String? = null,
    val countryCode: String = "IN",
    val expiresAtEpochSec: Long? = null
) {
    fun isExpired(nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        val exp = expiresAtEpochSec ?: JwtExpiry.expiresAtEpochSec(token) ?: return false
        // Treat as expired a minute early to avoid mid-request failures.
        return nowEpochSec >= (exp - 60L)
    }

    val isComplete: Boolean
        get() = token.isNotBlank() && vehicleUuid.isNotBlank() && !isExpired()
}

data class DiscoveredScooter(
    val uuid: String,
    val displayName: String,
    val registration: String? = null,
    val modelType: String? = null,
    val colour: String? = null
)

enum class AuthStep {
    PHONE,
    OTP,
    SCOOTER_SELECT,
    READY
}

data class AuthUiState(
    val step: AuthStep = AuthStep.PHONE,
    val phone: String = "",
    val otp: String = "",
    val countryCode: String = "IN",
    val resendAvailableAtMillis: Long = 0L,
    val pendingToken: String? = null,
    val scooters: List<DiscoveredScooter> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val session: AuthSession? = null
)
