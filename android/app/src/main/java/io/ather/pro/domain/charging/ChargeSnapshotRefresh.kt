package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ConnectionStatus

/** A new connection retrieves a cloud snapshot even when the existing socket is quiet. */
object ChargeSnapshotRefresh {
    const val INTERVAL_MS = 5_000L

    fun isDue(enabled: Boolean, connection: ConnectionStatus, connectedAtMs: Long?, nowMs: Long): Boolean =
        enabled && connection == ConnectionStatus.CONNECTED && connectedAtMs != null &&
            (nowMs < connectedAtMs || nowMs - connectedAtMs >= INTERVAL_MS)
}
