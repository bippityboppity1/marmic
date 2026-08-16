package com.marmic.plain

import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.marmic.plain.data.Settings
import com.marmic.plain.model.Layout
import com.marmic.plain.model.WidgetPage
import com.marmic.plain.model.WidgetSpec
import com.marmic.plain.ui.LauncherRoot
import com.marmic.plain.ui.theme.PlainTheme
import com.marmic.plain.widget.PlainAppWidgetHost
import com.marmic.plain.widget.WidgetInstaller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val plainApp: PlainApp get() = application as PlainApp

    private lateinit var appWidgetHost: PlainAppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var widgetInstaller: WidgetInstaller

    /** The page a pending widget install belongs to. */
    private var pendingPageId: String? = null

    /** Set when the host has been told to start listening, so we never unbalance it. */
    private var listening = false

    /** Bumped every time HOME is pressed while we are already showing. */
    private var homeRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = PlainAppWidgetHost(this)
        widgetInstaller = WidgetInstaller(
            activity = this,
            host = appWidgetHost,
            appWidgetManager = appWidgetManager,
            onWidgetReady = ::attachWidgetToPendingPage,
        )

        lifecycleScope.launch { pruneOrphanedWidgetIds() }

        setContent {
            val settings by plainApp.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = Settings.DEFAULT)
            val layout by plainApp.layoutRepository.layout
                .collectAsStateWithLifecycle(initialValue = Layout.EMPTY)
            val apps by plainApp.appRepository.apps.collectAsStateWithLifecycle()

            // FLAG_SHOW_WALLPAPER is a window flag, so it has to be applied to
            // the window rather than expressed in the composition.
            LaunchedEffect(settings.showWallpaper) {
                if (settings.showWallpaper) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                }
            }

            PlainTheme(settings) {
                LauncherRoot(
                    settings = settings,
                    layout = layout,
                    apps = apps,
                    host = appWidgetHost,
                    homeRequest = homeRequest,
                    onLaunchApp = { entry -> plainApp.appRepository.launch(entry) },
                    onAppInfo = { entry -> plainApp.appRepository.openAppInfo(entry) },
                    onUninstall = { entry -> plainApp.appRepository.requestUninstall(entry) },
                    isSystemApp = { entry -> plainApp.appRepository.isSystemApp(entry) },
                    onUpdateSettings = { transform ->
                        lifecycleScope.launch { plainApp.settingsRepository.update(transform) }
                    },
                    onToggleFavorite = { key ->
                        lifecycleScope.launch { plainApp.settingsRepository.toggleFavorite(key) }
                    },
                    onMoveFavorite = { key, delta ->
                        lifecycleScope.launch { plainApp.settingsRepository.moveFavorite(key, delta) }
                    },
                    onSetHidden = { key, hidden ->
                        lifecycleScope.launch { plainApp.settingsRepository.setHidden(key, hidden) }
                    },
                    onRenameApp = { key, label ->
                        lifecycleScope.launch { plainApp.settingsRepository.rename(key, label) }
                    },
                    onAddPage = ::addWidgetPage,
                    onRenamePage = ::renameWidgetPage,
                    onRemovePage = ::removeWidgetPage,
                    onPickWidget = { pageId, provider ->
                        pendingPageId = pageId
                        widgetInstaller.install(provider)
                    },
                    onUpdateWidget = ::updateWidget,
                    onRemoveWidget = ::removeWidget,
                    onSetDefaultLauncher = ::requestHomeRole,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // startListening can throw on devices with a corrupt widget database;
        // a launcher that cannot host widgets is still better than one that
        // will not start.
        runCatching { appWidgetHost.startListening() }
            .onSuccess { listening = true }
            .onFailure { Log.w(TAG, "AppWidgetHost.startListening failed", it) }
    }

    override fun onStop() {
        if (listening) {
            runCatching { appWidgetHost.stopListening() }
            listening = false
        }
        super.onStop()
    }

    // Deprecated, but unavoidable: a widget's configure activity is launched
    // through the system's IntentSender, which reports back the old way.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgetInstaller.onConfigureResult(requestCode, resultCode)
    }

    /**
     * Pressing home while already home should return to the home page rather
     * than doing nothing, which is what users expect from a launcher.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeRequest++
        }
    }

    private fun attachWidgetToPendingPage(appWidgetId: Int) {
        val pageId = pendingPageId
        pendingPageId = null
        if (pageId == null) {
            runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
            return
        }
        lifecycleScope.launch {
            plainApp.layoutRepository.update { layout ->
                layout.copy(
                    pages = layout.pages.map { page ->
                        if (page.id == pageId) {
                            page.copy(widgets = page.widgets + WidgetSpec(appWidgetId = appWidgetId))
                        } else {
                            page
                        }
                    },
                )
            }
        }
    }

    private fun addWidgetPage() {
        lifecycleScope.launch {
            plainApp.layoutRepository.update { layout ->
                layout.copy(pages = layout.pages + WidgetPage(id = UUID.randomUUID().toString()))
            }
        }
    }

    private fun renameWidgetPage(pageId: String, title: String) {
        lifecycleScope.launch {
            plainApp.layoutRepository.update { layout ->
                layout.copy(
                    pages = layout.pages.map { if (it.id == pageId) it.copy(title = title.trim()) else it },
                )
            }
        }
    }

    private fun removeWidgetPage(pageId: String) {
        lifecycleScope.launch {
            val removed = plainApp.layoutRepository.current().pages.firstOrNull { it.id == pageId }
            plainApp.layoutRepository.update { layout ->
                layout.copy(pages = layout.pages.filterNot { it.id == pageId })
            }
            // Release the host ids only after the layout no longer references them.
            removed?.widgets?.forEach { runCatching { appWidgetHost.deleteAppWidgetId(it.appWidgetId) } }
        }
    }

    private fun updateWidget(appWidgetId: Int, transform: (WidgetSpec) -> WidgetSpec) {
        lifecycleScope.launch {
            plainApp.layoutRepository.update { layout ->
                layout.copy(
                    pages = layout.pages.map { page ->
                        page.copy(
                            widgets = page.widgets.map { spec ->
                                if (spec.appWidgetId == appWidgetId) transform(spec) else spec
                            },
                        )
                    },
                )
            }
        }
    }

    private fun removeWidget(appWidgetId: Int) {
        lifecycleScope.launch {
            plainApp.layoutRepository.update { layout ->
                layout.copy(
                    pages = layout.pages.map { page ->
                        page.copy(widgets = page.widgets.filterNot { it.appWidgetId == appWidgetId })
                    },
                )
            }
            runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
        }
    }

    /**
     * Ids can be left allocated if the process dies between binding a widget and
     * persisting it. They cost a slot in the system's widget service, so drop
     * any the layout does not know about.
     */
    private suspend fun pruneOrphanedWidgetIds() {
        val known = plainApp.layoutRepository.layout.first().allWidgetIds.toSet()
        val allocated = runCatching { appWidgetHost.appWidgetIds }.getOrNull() ?: return
        allocated.filterNot { it in known }.forEach {
            runCatching { appWidgetHost.deleteAppWidgetId(it) }
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                val granted = runCatching {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    true
                }.getOrDefault(false)
                if (granted) return
            }
        }
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
        }.onFailure { Log.w(TAG, "No home settings screen on this device", it) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { Log.w(TAG, "No accessibility settings screen on this device", it) }
    }
}
