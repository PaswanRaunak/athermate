package io.ather.pro.ble

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.ather.pro.appContainer

/**
 * Notification bridge — faithful port of the real app's routing
 * (ble_mobile_library.NotificationListener): dispatch by notification.category
 *
 *   "msg"       + WhatsApp/WA-Business + not a group summary → message to dash (t=4)
 *   "call"      + WhatsApp/WA-Business                                  → WhatsApp call (t=1, whatsapp=true)
 *   "call"      + any other package (dialer)                            → capture caller name; the
 *                 TelephonyCallback feed in VehicleLinkController drives the state, exactly
 *                 like the real CallManager (state from TelephonyManager, name from the
 *                 call notification's android.callPerson last-10-digits or android.title)
 *
 * Needs one-time user grant: Settings → Notifications → App settings → AtherMate →
 * Notification access.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // MediaBridge needs this component for MediaSessionManager.getActiveSessions.
        io.ather.pro.ble.MediaBridge.listenerComponent = ComponentName(this, javaClass)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        var category = n.category
        if (category == null) n.publicVersion?.let { category = it.category }

        when (category) {
            NotificationConstant_MSG -> handleMessage(sbn)
            NotificationConstant_CALL -> handleCall(sbn)
            NotificationConstant_TRANSPORT -> MediaBridge.onTransportNotification(applicationContext, sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        var category = n.category
        if (category == null && sbn.packageName == "com.google.android.dialer") category = NotificationConstant_CALL
        if (category == null) n.publicVersion?.let { category = it.category }
        if (category != NotificationConstant_CALL) return

        if (sbn.packageName in WHATSAPP_PACKAGES) {
            // WhatsApp call ended → clear state on the dash.
            runCatching { applicationContext.appContainer.vehicleLink }.getOrNull()
                ?.onWhatsAppCallEnded()
        } else {
            runCatching { applicationContext.appContainer.vehicleLink }.getOrNull()
                ?.onCallerNameCaptured("")
        }
    }

    // ---------------------------------------------------------------- messages (t=4)

    private fun handleMessage(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras

        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (text.isNullOrBlank()) return

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroup = conversationTitle != null ||
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val from = if (isGroup) (title ?: conversationTitle ?: "WhatsApp")
                   else (title ?: conversationTitle ?: "WhatsApp")

        val key = "${sbn.packageName}|$from|$text"
        val now = System.currentTimeMillis()
        synchronized(lastPosted) {
            val last = lastPosted[key]
            if (last != null && now - last < 3_000) return
            lastPosted[key] = now
            if (lastPosted.size > 60) {
                val cutoff = now - 60_000
                lastPosted.entries.removeIf { it.value < cutoff }
            }
        }

        runCatching { applicationContext.appContainer.vehicleLink }.getOrNull()
            ?.sendWhatsapp(from, text, isGroup)
    }

    // ---------------------------------------------------------------- calls (t=1)

    private fun handleCall(sbn: StatusBarNotification) {
        val vehicleLink = runCatching { applicationContext.appContainer.vehicleLink }.getOrNull() ?: return
        val extras = sbn.notification.extras

        if (sbn.packageName in WHATSAPP_PACKAGES) {
            // WhatsApp call: ringing notification is posted first; the ongoing
            // "on a call" one is isOngoing → treat as answered (offhook).
            val name = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: "WhatsApp call"
            val state = if (sbn.isOngoing) 2 else 1
            vehicleLink.onWhatsAppCall(name, state)
            return
        }

        // Dialer call notification → caller-name capture (real CallManager.parseData):
        // android.callPerson URI's last 10 digits, else android.title.
        val person = extras.getParcelable<android.app.Person>("android.callPerson")
        var name = ""
        val personUri: String? = person?.uri
        if (personUri != null && personUri.length > 10) {
            val digits = personUri.takeLast(10)
            if (digits.length == 10 && digits.all { it.isDigit() }) name = digits
        }
        if (name.isBlank()) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            if (!(title.equals("call", true) && android.os.Build.MANUFACTURER.equals("samsung", true))) {
                name = title
            }
        }
        if (name.isNotBlank()) vehicleLink.onCallerNameCaptured(name)
    }

    companion object {
        // Real NotificationConstant values.
        private const val NotificationConstant_MSG = "msg"
        private const val NotificationConstant_CALL = "call"
        private const val NotificationConstant_TRANSPORT = "transport"

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val lastPosted = HashMap<String, Long>()
        internal var waCallActions: Array<Notification.Action>? = null
        private val waCallActionsLock = Any()

        /** True if the user granted this app notification-listener access. */
        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(':').any {
                ComponentName.unflattenFromString(it)?.packageName == context.packageName
            }
        }
    }
}
