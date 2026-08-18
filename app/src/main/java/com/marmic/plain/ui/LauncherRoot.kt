package com.marmic.plain.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import com.marmic.plain.data.Settings
import com.marmic.plain.data.WidgetTint
import com.marmic.plain.model.AppEntry
import com.marmic.plain.model.HomeLayout
import com.marmic.plain.model.WidgetSpec
import com.marmic.plain.system.NotificationCounts
import com.marmic.plain.system.PlainAccessibilityService
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.widget.PlainAppWidgetHost

private enum class Overlay { NONE, DRAWER, SETTINGS, WIDGET_PICKER }

@Composable
fun LauncherRoot(
    settings: Settings,
    layout: HomeLayout,
    apps: List<AppEntry>,
    host: PlainAppWidgetHost,
    homeRequest: Int,
    onLaunchApp: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    isSystemApp: (AppEntry) -> Boolean,
    onUpdateSettings: (((Settings) -> Settings)) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMoveFavorite: (String, Int) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onRenameApp: (String, String?) -> Unit,
    onPickWidget: (AppWidgetProviderInfo) -> Unit,
    onSetWidgetHeight: (Int, Int) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
    onStackWidget: (Int) -> Unit,
    onUnstackWidget: (Int) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onSetDefaultLauncher: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalPlainColors.current

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    var renameApp by remember { mutableStateOf<AppEntry?>(null) }
    var menuWidgetId by remember { mutableStateOf<Int?>(null) }

    val notificationCounts by NotificationCounts.counts.collectAsState()
    val badgeFor: (AppEntry) -> Int = { entry ->
        if (settings.notificationBadges) notificationCounts[entry.packageName] ?: 0 else 0
    }

    // Widgets render themselves; the only thing we control is what their pixels
    // get composited through on the way to the screen.
    val duotone = when (settings.widgetTint) {
        WidgetTint.OFF -> null
        WidgetTint.PALETTE -> colors.background to colors.foreground
    }

    val labelFor: (AppEntry) -> String = { entry -> settings.renames[entry.key] ?: entry.label }
    val visibleApps = remember(apps, settings.hidden) { apps.filterNot { it.key in settings.hidden } }
    val favorites = remember(apps, settings.favorites) {
        settings.favorites.mapNotNull { key -> apps.firstOrNull { it.key == key } }
    }

    // Long-pressing a hosted widget has to come back through the host, since the
    // touch is delivered to the widget's own view hierarchy.
    DisposableEffect(host) {
        host.onWidgetLongPress = { id -> menuWidgetId = id }
        onDispose { host.onWidgetLongPress = null }
    }

    // Pressing home while already here closes whatever is open.
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) overlay = Overlay.NONE
    }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    val background = if (settings.showWallpaper) {
        colors.background.copy(alpha = settings.wallpaperDim)
    } else {
        colors.background
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(background),
    ) {
        HomePage(
            layout = layout,
            host = host,
            favorites = favorites,
            labelFor = labelFor,
            badgeFor = badgeFor,
            duotone = duotone,
            onLaunch = onLaunchApp,
            onAppLongPress = { menuApp = it },
            onOpenDrawer = { overlay = Overlay.DRAWER },
            onOpenSettings = { overlay = Overlay.SETTINGS },
            onSwipeDown = {
                if (settings.swipeDownForNotifications) {
                    PlainAccessibilityService.openNotifications()
                }
            },
            onDoubleTap = {
                if (settings.doubleTapToLock) {
                    PlainAccessibilityService.lockScreen()
                }
            },
            onAddWidget = { overlay = Overlay.WIDGET_PICKER },
            onWidgetLongPress = { id -> menuWidgetId = id },
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        )

        AnimatedVisibility(
            visible = overlay == Overlay.DRAWER,
            enter = slideInVertically { it / 4 } + fadeIn(),
            exit = slideOutVertically { it / 4 } + fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                AppDrawer(
                    apps = visibleApps,
                    labelFor = labelFor,
                    badgeFor = badgeFor,
                    onLaunch = { entry ->
                        overlay = Overlay.NONE
                        onLaunchApp(entry)
                    },
                    onAppLongPress = { menuApp = it },
                    onClose = { overlay = Overlay.NONE },
                )
            }
        }

        AnimatedVisibility(visible = overlay == Overlay.SETTINGS, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                SettingsScreen(
                    settings = settings,
                    layout = layout,
                    apps = apps,
                    labelFor = labelFor,
                    accessibilityEnabled = PlainAccessibilityService.isEnabled(context),
                    notificationAccessEnabled = NotificationCounts.isEnabled(context),
                    onUpdateSettings = onUpdateSettings,
                    onToggleFavorite = onToggleFavorite,
                    onMoveFavorite = onMoveFavorite,
                    onSetHidden = onSetHidden,
                    onAddWidget = { overlay = Overlay.WIDGET_PICKER },
                    onWidgetMenu = { appWidgetId -> menuWidgetId = appWidgetId },
                    onSetDefaultLauncher = onSetDefaultLauncher,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onClose = { overlay = Overlay.NONE },
                )
            }
        }

        AnimatedVisibility(visible = overlay == Overlay.WIDGET_PICKER, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                WidgetPicker(
                    onPick = { provider ->
                        onPickWidget(provider)
                        overlay = Overlay.NONE
                    },
                    onClose = { overlay = Overlay.NONE },
                )
            }
        }
    }

    menuApp?.let { entry ->
        val isFavorite = entry.key in settings.favorites
        PMenuDialog(
            title = labelFor(entry),
            onDismiss = { menuApp = null },
            actions = buildList<Pair<String, () -> Unit>> {
                add((if (isFavorite) "remove from home" else "add to home") to {
                    onToggleFavorite(entry.key)
                    menuApp = null
                })
                add("rename" to { renameApp = entry; menuApp = null })
                add("hide" to { onSetHidden(entry.key, true); menuApp = null })
                add("app info" to { onAppInfo(entry); menuApp = null })
                if (!isSystemApp(entry)) {
                    add("uninstall" to { onUninstall(entry); menuApp = null })
                }
            },
        )
    }

    renameApp?.let { entry ->
        PTextPromptDialog(
            title = "rename ${entry.label}",
            initialValue = labelFor(entry),
            placeholder = entry.label,
            onConfirm = { label ->
                onRenameApp(entry.key, label)
                renameApp = null
            },
            onDismiss = { renameApp = null },
            extraActions = listOf(
                "reset" to {
                    onRenameApp(entry.key, null)
                    renameApp = null
                },
            ),
        )
    }

    menuWidgetId?.let { widgetId ->
        val slot = layout.slotOf(widgetId)
        val index = layout.slots.indexOfFirst { it.id == slot?.id }
        PMenuDialog(
            title = if (slot?.isStack == true) "widget · in a stack of ${slot.appWidgetIds.size}" else "widget",
            onDismiss = { menuWidgetId = null },
            actions = buildList<Pair<String, () -> Unit>> {
                if (index > 0) {
                    add("stack onto the one above" to { onStackWidget(widgetId); menuWidgetId = null })
                }
                if (slot?.isStack == true) {
                    add("take out of the stack" to { onUnstackWidget(widgetId); menuWidgetId = null })
                }
                WidgetSpec.HEIGHT_PRESETS.forEach { height ->
                    add("size $height" to {
                        onSetWidgetHeight(widgetId, height)
                        menuWidgetId = null
                    })
                }
                if (index > 0) {
                    add("move up" to { onMoveWidget(widgetId, -1); menuWidgetId = null })
                }
                if (index >= 0 && index < layout.slots.lastIndex) {
                    add("move down" to { onMoveWidget(widgetId, 1); menuWidgetId = null })
                }
                add("add another widget" to {
                    menuWidgetId = null
                    overlay = Overlay.WIDGET_PICKER
                })
                // A tall widget can cover everything that is long-pressable on
                // the home screen, so settings needs a way out from in here.
                add("launcher settings" to {
                    menuWidgetId = null
                    overlay = Overlay.SETTINGS
                })
                add("remove widget" to {
                    onRemoveWidget(widgetId)
                    menuWidgetId = null
                })
            },
        )
    }
}
