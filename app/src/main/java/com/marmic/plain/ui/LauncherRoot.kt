package com.marmic.plain.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marmic.plain.data.Settings
import com.marmic.plain.model.AppEntry
import com.marmic.plain.model.Layout
import com.marmic.plain.model.WidgetSpec
import com.marmic.plain.system.PlainAccessibilityService
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.widget.PlainAppWidgetHost

private enum class Overlay { NONE, DRAWER, SETTINGS, WIDGET_PICKER }

private val WIDGET_HEIGHT_PRESETS = listOf(140, 200, 260, 340, 440)

@Composable
fun LauncherRoot(
    settings: Settings,
    layout: Layout,
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
    onAddPage: () -> Unit,
    onRenamePage: (String, String) -> Unit,
    onRemovePage: (String) -> Unit,
    onPickWidget: (String, AppWidgetProviderInfo) -> Unit,
    onUpdateWidget: (Int, (WidgetSpec) -> WidgetSpec) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onSetDefaultLauncher: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalPlainColors.current

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var pickerPageId by remember { mutableStateOf<String?>(null) }
    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    var renameApp by remember { mutableStateOf<AppEntry?>(null) }
    var menuWidgetId by remember { mutableStateOf<Int?>(null) }

    val labelFor: (AppEntry) -> String = { entry -> settings.renames[entry.key] ?: entry.label }
    val visibleApps = remember(apps, settings.hidden) { apps.filterNot { it.key in settings.hidden } }
    val favorites = remember(apps, settings.favorites) {
        settings.favorites.mapNotNull { key -> apps.firstOrNull { it.key == key } }
    }

    val pagerState = rememberPagerState(pageCount = { 1 + layout.pages.size })

    // Long-pressing a hosted widget has to come back through the host, since the
    // touch is delivered to the widget's own view hierarchy.
    DisposableEffect(host) {
        host.onWidgetLongPress = { id -> menuWidgetId = id }
        onDispose { host.onWidgetLongPress = null }
    }

    // Pressing home while already here returns to the home page.
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) {
            overlay = Overlay.NONE
            if (pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
        }
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) { pageIndex ->
            if (pageIndex == 0) {
                HomePage(
                    favorites = favorites,
                    labelFor = labelFor,
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
                )
            } else {
                val page = layout.pages[pageIndex - 1]
                WidgetPageView(
                    page = page,
                    host = host,
                    onAddWidget = {
                        pickerPageId = page.id
                        overlay = Overlay.WIDGET_PICKER
                    },
                    onWidgetLongPress = { spec -> menuWidgetId = spec.appWidgetId },
                )
            }
        }

        if (layout.pages.isNotEmpty()) {
            PageIndicator(
                pageCount = 1 + layout.pages.size,
                current = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 6.dp),
            )
        }

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
                    onUpdateSettings = onUpdateSettings,
                    onToggleFavorite = onToggleFavorite,
                    onMoveFavorite = onMoveFavorite,
                    onSetHidden = onSetHidden,
                    onAddPage = onAddPage,
                    onRenamePage = onRenamePage,
                    onRemovePage = onRemovePage,
                    onAddWidget = { pageId ->
                        pickerPageId = pageId
                        overlay = Overlay.WIDGET_PICKER
                    },
                    onSetDefaultLauncher = onSetDefaultLauncher,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
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
                        pickerPageId?.let { pageId -> onPickWidget(pageId, provider) }
                        pickerPageId = null
                        overlay = Overlay.NONE
                    },
                    onClose = {
                        pickerPageId = null
                        overlay = Overlay.NONE
                    },
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
        val spec = layout.pages.firstNotNullOfOrNull { page ->
            page.widgets.firstOrNull { it.appWidgetId == widgetId }
        }
        PMenuDialog(
            title = "widget",
            onDismiss = { menuWidgetId = null },
            actions = buildList<Pair<String, () -> Unit>> {
                add(
                    (if (spec?.fillPage == true) "use a fixed height" else "fill the page") to {
                        onUpdateWidget(widgetId) { it.copy(fillPage = !it.fillPage) }
                        menuWidgetId = null
                    },
                )
                if (spec?.fillPage != true) {
                    WIDGET_HEIGHT_PRESETS.forEach { height ->
                        add("height $height dp" to {
                            onUpdateWidget(widgetId) { it.copy(heightDp = height, fillPage = false) }
                            menuWidgetId = null
                        })
                    }
                }
                add("remove widget" to {
                    onRemoveWidget(widgetId)
                    menuWidgetId = null
                })
            },
        )
    }
}

/** Page position, drawn as text rather than as dots. */
@Composable
private fun PageIndicator(pageCount: Int, current: Int, modifier: Modifier = Modifier) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            PText(
                text = if (index == current) "—" else "·",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = type.label,
                color = if (index == current) colors.foreground else colors.dim,
                transformCase = false,
            )
        }
    }
}
