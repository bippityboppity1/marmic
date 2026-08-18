package com.marmic.plain.system

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How many notifications each package currently has waiting.
 *
 * Kept in a singleton rather than bound to the service instance because the
 * system starts and stops the listener on its own schedule, while the launcher
 * wants to read the last known counts either way.
 */
object NotificationCounts {

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    internal fun set(counts: Map<String, Int>) {
        _counts.value = counts
    }

    internal fun clear() {
        _counts.value = emptyMap()
    }

    /** Whether the user has granted notification access in system settings. */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val expected = ComponentName(context, PlainNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}

/**
 * Counts what is in the shade, per app, so the launcher can badge a name.
 *
 * Only the notifications a person would think of as "waiting" are counted:
 * group summaries would double-count their own children, and ongoing entries
 * are things like a running download or a media player, which are not news.
 */
class PlainNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationCounts.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val counts = active
            .filter { it.isClearable }
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .groupingBy { it.packageName }
            .eachCount()
        NotificationCounts.set(counts)
    }
}
