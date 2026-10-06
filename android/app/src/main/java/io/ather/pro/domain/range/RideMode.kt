package io.ather.pro.domain.range

import io.ather.pro.domain.model.ScooterModel

/** Stable API keys, display names and model capabilities shared by the app and widget. */
enum class RideMode(val apiName: String, val displayName: String) {
    SMART_ECO("SmartEco", "SmartEco"),
    ECO("Eco", "Eco"),
    RIDE("Ride", "Ride"),
    SPORT("Sport", "Sport"),
    WARP("Warp", "Warp"),
    WARP_PLUS("WarpPlus", "Warp+"),
    ZIP("Zip", "Zip"),
    POWER("Power", "Power");

    fun supportedBy(model: ScooterModel?): Boolean = when (model) {
        ScooterModel.ATHER_APEX -> this != WARP && this != ZIP && this != POWER
        ScooterModel.ATHER_450X_3_7, ScooterModel.ATHER_450X_2_9 -> this != WARP_PLUS && this != ZIP && this != POWER
        ScooterModel.ATHER_450S -> this in setOf(SMART_ECO, ECO, RIDE, SPORT)
        ScooterModel.ATHER_RIZTA_3_7, ScooterModel.ATHER_RIZTA_2_9 -> this in setOf(SMART_ECO, ECO, ZIP)
        ScooterModel.ATHER_KONARC_2_1,
        ScooterModel.ATHER_KONARC_2_7,
        ScooterModel.ATHER_KONARC_3_5 -> this in setOf(SMART_ECO, ECO, POWER)
        null -> this != WARP_PLUS && this != POWER // Never imply Apex support without a known model.
    }

    companion object {
        fun from(value: String?): RideMode? = when (
            value.orEmpty().lowercase().replace("+", "plus").filter(Char::isLetterOrDigit)
        ) {
            "smarteco" -> SMART_ECO
            "eco" -> ECO
            "ride" -> RIDE
            "sport" -> SPORT
            "warp" -> WARP
            "warpplus" -> WARP_PLUS
            "zip" -> ZIP
            "power" -> POWER
            else -> null
        }
    }
}
