package io.ather.pro.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.ather.pro.appContainer

/**
 * Quick Settings tile for the vehicle link — the counterpart of the original
 * app's BTTileService: shows link state in the pull-down shade and toggles
 * the link with one tap.
 */
class VehicleLinkTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        val controller = applicationContext.appContainer.vehicleLink
        controller.setEnabled(!controller.state.value.enabled)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val st = runCatching { applicationContext.appContainer.vehicleLink.state.value }.getOrNull()
        tile.label = "Vehicle link"
        tile.subtitle = when {
            st == null -> "Off"
            st.link == io.ather.pro.ble.VehicleLinkController.LinkStatus.CONNECTED -> st.deviceName ?: "Connected"
            st.enabled -> "Searching…"
            else -> "Off"
        }
        tile.state = if (st?.enabled == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
