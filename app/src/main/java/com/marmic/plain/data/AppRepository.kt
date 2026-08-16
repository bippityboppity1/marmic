package com.marmic.plain.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.marmic.plain.model.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AppRepository"

/**
 * The installed-app list, kept live via [LauncherApps.Callback].
 *
 * Uses LauncherApps rather than PackageManager queries so that work-profile
 * apps and per-user launching are handled correctly.
 */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
        override fun onPackagesAvailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            _apps.value = loadApps()
        }
    }

    private fun loadApps(): List<AppEntry> {
        val profiles = runCatching { userManager.userProfiles }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Process.myUserHandle())

        val entries = mutableListOf<AppEntry>()
        for (user in profiles) {
            val serial = runCatching { userManager.getSerialNumberForUser(user) }.getOrDefault(0L)
            val activities = runCatching { launcherApps.getActivityList(null, user) }
                .onFailure { Log.w(TAG, "getActivityList failed for $user", it) }
                .getOrDefault(emptyList())

            for (activity in activities) {
                entries += AppEntry(
                    componentName = activity.componentName,
                    user = user,
                    userSerial = serial,
                    label = activity.label?.toString().orEmpty().ifBlank { activity.componentName.packageName },
                )
            }
        }
        return entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launch(entry: AppEntry, sourceBounds: Rect? = null, opts: Bundle? = null): Boolean = runCatching {
        launcherApps.startMainActivity(entry.componentName, entry.user, sourceBounds, opts)
    }.onFailure { Log.w(TAG, "Could not launch ${entry.componentName}", it) }.isSuccess

    fun openAppInfo(entry: AppEntry): Boolean = runCatching {
        launcherApps.startAppDetailsActivity(entry.componentName, entry.user, null, null)
    }.onFailure { Log.w(TAG, "Could not open app info for ${entry.componentName}", it) }.isSuccess

    fun requestUninstall(entry: AppEntry): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { Log.w(TAG, "Could not request uninstall of ${entry.packageName}", it) }.isSuccess

    /** True for apps the system will not let us uninstall, so the menu can hide the option. */
    fun isSystemApp(entry: AppEntry): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(entry.packageName, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    fun find(key: String): AppEntry? = _apps.value.firstOrNull { it.key == key }

    companion object {
        fun keyOf(userSerial: Long, component: ComponentName) = AppEntry.key(userSerial, component)
    }
}
