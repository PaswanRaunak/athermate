package io.ather.pro.domain.chargingmap

/**
 * Domain models for public charger locations and wallet, shaped after Mether's
 * Cerberus contracts (`/api/v2/locations`, `/api/v1/wallet`).
 * Optional fields stay null when the API omits them — never fabricated.
 */
data class ChargerLocation(
    val name: String?,
    val infraType: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isOpenNow: Boolean?,
    val closingIn: String?,
    val nextOpening: String?,
    val locationTags: List<String>,
    val dbsAvailable: Int?,
    val dbsInUse: Int?,
    val dbsUnderMaintenance: Int?,
    val dbsOutOfOperatingHours: Int?,
    val dbsTotal: Int?,
    val connectors: List<ChargerConnector>,
    val tariffLines: List<TariffLine>,
    val partyId: String?,
    val has6kwGrid: Boolean?,
)

data class ChargerConnector(
    val displayText: String?,
    val standard: String?,
)

data class TariffLine(
    val displayText: String?,
    val text: String?,
)

data class WalletSnapshot(
    val balance: Double?,
    val walletStatus: String?,
    /** Present only when the wallet payload includes credits. */
    val credits: Double?,
    /** Present only when the wallet payload includes a transactions array. */
    val transactions: List<WalletTransaction>,
)

data class WalletTransaction(
    val id: String?,
    val title: String?,
    val amount: Double?,
    val currency: String?,
    val timestamp: String?,
    val type: String?,
)

sealed class ChargingMapLoadState<out T> {
    data object Idle : ChargingMapLoadState<Nothing>()
    data object Loading : ChargingMapLoadState<Nothing>()
    data class Ready<T>(val value: T) : ChargingMapLoadState<T>()
    data class Empty(val message: String) : ChargingMapLoadState<Nothing>()
    data class Error(val message: String) : ChargingMapLoadState<Nothing>()
}
