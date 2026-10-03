package io.ather.pro

import android.app.Application
import android.content.Context
import io.ather.pro.data.auth.AtherAuthApi
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import io.ather.pro.service.MonitoringController

/** Application lifetime dependencies; activities never own the scooter connection. */
class AtherApplication : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        io.ather.pro.domain.computation.TelemetryComputation.engine =
            io.ather.pro.data.computation.RustTelemetryMath
    }
}

class AppContainer(context: Context) {
    val sessionStore = SecureSessionStore(context)
    val authApi = AtherAuthApi()
    val repository = AtherRepository.getInstance(context)
    val monitoring = MonitoringController(context, repository, sessionStore)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AtherApplication).container
