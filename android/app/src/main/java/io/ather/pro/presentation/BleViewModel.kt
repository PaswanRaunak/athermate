package io.ather.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.ather.pro.ble.BleDeviceUi
import io.ather.pro.ble.ScooterBleManager

/** Thin UI bridge over the application-scoped BLE manager. */
class BleViewModel(private val ble: ScooterBleManager) : ViewModel() {
    val ui = ble.state

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()
    fun connect(device: BleDeviceUi) = ble.connect(device)
    fun disconnect() = ble.disconnect()
    fun clearLogs() = ble.clearLogs()

    class Factory(private val ble: ScooterBleManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BleViewModel(ble) as T
    }
}
