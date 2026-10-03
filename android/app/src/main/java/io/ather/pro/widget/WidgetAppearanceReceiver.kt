package io.ather.pro.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Repaint cached readings on appearance/time changes; never starts a scooter request. */
class WidgetAppearanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DashboardWidgetUpdater.refreshAll(context)
    }

    companion object {
        fun register(context: Context) {
            ContextCompat.registerReceiver(context.applicationContext, WidgetAppearanceReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
}
