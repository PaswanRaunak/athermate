package io.ather.pro.domain.monitoring

data class MonitoringState(
    val alwaysEnabled: Boolean = true,
    val running: Boolean = false,
    val error: String? = null
)

object MonitoringPolicy {
    fun shouldRun(signedIn: Boolean, alwaysEnabled: Boolean, limitEnabled: Boolean,
        charging: Boolean, awaitingStop: Boolean = false): Boolean =
        signedIn && (alwaysEnabled || limitEnabled) && (limitEnabled || charging || awaitingStop)
}
