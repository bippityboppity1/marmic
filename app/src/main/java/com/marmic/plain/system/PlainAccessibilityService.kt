package com.marmic.plain.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Two global actions the launcher cannot perform on its own: locking the screen
 * and opening the notification shade. Deliberately configured to retrieve no
 * window content (see accessibility_service_config.xml) — it only ever pushes
 * actions out, it never reads the screen.
 */
class PlainAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: PlainAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** @return false when the service is not enabled, so callers can prompt. */
        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }

        fun openNotifications(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }

        /**
         * Whether the user has switched us on in system settings. [isRunning] can
         * lag a fresh enable, so consult the secure setting too.
         */
        fun isEnabled(context: Context): Boolean {
            if (isRunning) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val expected = ComponentName(context, PlainAccessibilityService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }
    }
}
