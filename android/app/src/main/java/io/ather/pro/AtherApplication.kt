package io.ather.pro

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import io.ather.pro.data.auth.AtherAuthApi
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import io.ather.pro.ble.ScooterBleManager
import io.ather.pro.service.MonitoringController

/** Application lifetime dependencies; activities never own the scooter connection. */
class AtherApplication : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        io.ather.pro.data.update.AppUpdateWorker.schedule(this)
        io.ather.pro.widget.WidgetAppearanceReceiver.register(this)
        io.ather.pro.widget.DashboardWidgetUpdater.refreshAll(this)
        io.ather.pro.domain.computation.TelemetryComputation.engine =
            io.ather.pro.data.computation.RustTelemetryMath
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        io.ather.pro.widget.DashboardWidgetUpdater.refreshAll(this)
    }
}

class AppContainer(context: Context) {
    val sessionStore = SecureSessionStore(context)
    val authApi = AtherAuthApi()
    val repository = AtherRepository.getInstance(context)
    val monitoring = MonitoringController(context, repository, sessionStore)
    val ble = ScooterBleManager(context)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AtherApplication).container
