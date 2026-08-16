package com.marmic.plain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marmic.plain.data.ClockSize
import com.marmic.plain.data.Palette
import com.marmic.plain.data.Settings
import com.marmic.plain.data.Typeface
import com.marmic.plain.model.AppEntry
import com.marmic.plain.model.Layout
import com.marmic.plain.model.WidgetPage
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography

private enum class SettingsRoute { ROOT, APPEARANCE, HOME, APPS, WIDGETS, GESTURES }

private val TEXT_SCALES = listOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
private val TEXT_SCALE_LABELS = listOf("xs", "s", "m", "l", "xl", "xxl")

@Composable
fun SettingsScreen(
    settings: Settings,
    layout: Layout,
    apps: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    accessibilityEnabled: Boolean,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMoveFavorite: (String, Int) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onAddPage: () -> Unit,
    onRenamePage: (String, String) -> Unit,
    onRemovePage: (String) -> Unit,
    onAddWidget: (String) -> Unit,
    onSetDefaultLauncher: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var route by remember { mutableStateOf(SettingsRoute.ROOT) }

    BackHandler {
        if (route == SettingsRoute.ROOT) onClose() else route = SettingsRoute.ROOT
    }

    val back: () -> Unit = {
        if (route == SettingsRoute.ROOT) onClose() else route = SettingsRoute.ROOT
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            when (route) {
                SettingsRoute.ROOT -> RootSettings(onNavigate = { route = it })

                SettingsRoute.APPEARANCE -> AppearanceSettings(settings, onUpdateSettings)

                SettingsRoute.HOME -> HomeSettings(
                    settings = settings,
                    apps = apps,
                    labelFor = labelFor,
                    onUpdateSettings = onUpdateSettings,
                    onToggleFavorite = onToggleFavorite,
                    onMoveFavorite = onMoveFavorite,
                )

                SettingsRoute.APPS -> AppsSettings(
                    settings = settings,
                    apps = apps,
                    labelFor = labelFor,
                    onUpdateSettings = onUpdateSettings,
                    onSetHidden = onSetHidden,
                )

                SettingsRoute.WIDGETS -> WidgetSettings(
                    layout = layout,
                    onAddPage = onAddPage,
                    onRenamePage = onRenamePage,
                    onRemovePage = onRemovePage,
                    onAddWidget = onAddWidget,
                )

                SettingsRoute.GESTURES -> GestureSettings(
                    settings = settings,
                    accessibilityEnabled = accessibilityEnabled,
                    onUpdateSettings = onUpdateSettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onSetDefaultLauncher = onSetDefaultLauncher,
                )
            }
        }

        PActionBar(actions = listOf((if (route == SettingsRoute.ROOT) "close" else "back") to back))
    }
}

@Composable
private fun RootSettings(onNavigate: (SettingsRoute) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PSectionHeader("settings")
        PValueRow("appearance", "palette, type, wallpaper", { onNavigate(SettingsRoute.APPEARANCE) })
        PValueRow("home", "clock, date, favourites", { onNavigate(SettingsRoute.HOME) })
        PValueRow("apps", "hidden apps, search", { onNavigate(SettingsRoute.APPS) })
        PValueRow("widgets", "pages and widgets", { onNavigate(SettingsRoute.WIDGETS) })
        PValueRow("gestures", "lock, notifications, default launcher", { onNavigate(SettingsRoute.GESTURES) })
    }
}

@Composable
private fun AppearanceSettings(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListEdgePadding) {
        item { PSectionHeader("appearance") }
        item {
            POptionRow(
                title = "palette",
                options = Palette.entries.toList(),
                selected = settings.palette,
                label = { it.label },
                onSelect = { palette -> onUpdate { it.copy(palette = palette) } },
            )
        }
        item {
            POptionRow(
                title = "typeface",
                options = Typeface.entries.toList(),
                selected = settings.typeface,
                label = { it.label },
                onSelect = { typeface -> onUpdate { it.copy(typeface = typeface) } },
            )
        }
        item {
            POptionRow(
                title = "text size",
                options = TEXT_SCALES,
                selected = TEXT_SCALES.minByOrNull { kotlin.math.abs(it - settings.textScale) } ?: 1f,
                label = { scale -> TEXT_SCALE_LABELS[TEXT_SCALES.indexOf(scale)] },
                onSelect = { scale -> onUpdate { it.copy(textScale = scale) } },
            )
        }
        item {
            PToggleRow(
                title = "lowercase everything",
                checked = settings.lowercase,
                onCheckedChange = { value -> onUpdate { it.copy(lowercase = value) } },
            )
        }
        item { PDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            PToggleRow(
                title = "show wallpaper",
                subtitle = "off means a flat background, which is what makes the palette exact",
                checked = settings.showWallpaper,
                onCheckedChange = { value -> onUpdate { it.copy(showWallpaper = value) } },
            )
        }
        if (settings.showWallpaper) {
            item {
                POptionRow(
                    title = "wallpaper dim",
                    options = listOf(0f, 0.25f, 0.45f, 0.7f, 0.9f),
                    selected = settings.wallpaperDim,
                    label = { "${(it * 100).toInt()}%" },
                    onSelect = { dim -> onUpdate { it.copy(wallpaperDim = dim) } },
                )
            }
        }
    }
}

@Composable
private fun HomeSettings(
    settings: Settings,
    apps: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMoveFavorite: (String, Int) -> Unit,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    val favorites = settings.favorites.mapNotNull { key -> apps.firstOrNull { it.key == key } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListEdgePadding) {
        item { PSectionHeader("clock") }
        item {
            POptionRow(
                title = "clock size",
                options = ClockSize.entries.toList(),
                selected = settings.clockSize,
                label = { it.label },
                onSelect = { size -> onUpdateSettings { it.copy(clockSize = size) } },
            )
        }
        item {
            PToggleRow(
                title = "24-hour clock",
                checked = settings.clock24h,
                onCheckedChange = { value -> onUpdateSettings { it.copy(clock24h = value) } },
            )
        }
        item {
            PToggleRow(
                title = "show date",
                checked = settings.showDate,
                onCheckedChange = { value -> onUpdateSettings { it.copy(showDate = value) } },
            )
        }
        item {
            PToggleRow(
                title = "show battery",
                checked = settings.showBattery,
                onCheckedChange = { value -> onUpdateSettings { it.copy(showBattery = value) } },
            )
        }

        item { PSectionHeader("favourites · ${favorites.size}/${Settings.MAX_FAVORITES}") }
        if (favorites.isEmpty()) {
            item { PEmptyState("no favourites yet — pick apps from the list below") }
        }
        items(favorites, key = { "fav-${it.key}" }) { entry ->
            Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = 8.dp)) {
                PText(labelFor(entry), style = type.item)
                PActionBar(
                    horizontalPadding = 0.dp,
                    actions = listOf(
                        "up" to { onMoveFavorite(entry.key, -1) },
                        "down" to { onMoveFavorite(entry.key, 1) },
                        "remove" to { onToggleFavorite(entry.key) },
                    ),
                )
            }
        }

        item { PSectionHeader("add to favourites") }
        items(apps.filter { it.key !in settings.favorites && it.key !in settings.hidden }, key = { "all-${it.key}" }) { entry ->
            PRow(onClick = { onToggleFavorite(entry.key) }) {
                PText(labelFor(entry), modifier = Modifier.weight(1f), style = type.item, color = colors.foreground)
            }
        }
    }
}

@Composable
private fun AppsSettings(
    settings: Settings,
    apps: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListEdgePadding) {
        item { PSectionHeader("search") }
        item {
            PToggleRow(
                title = "open keyboard with the drawer",
                checked = settings.searchAutoKeyboard,
                onCheckedChange = { value -> onUpdateSettings { it.copy(searchAutoKeyboard = value) } },
            )
        }
        item {
            PToggleRow(
                title = "launch on a single match",
                subtitle = "opens the app as soon as the search narrows to one result",
                checked = settings.launchOnSingleMatch,
                onCheckedChange = { value -> onUpdateSettings { it.copy(launchOnSingleMatch = value) } },
            )
        }

        item { PSectionHeader("hidden apps · ${settings.hidden.size}") }
        item {
            PText(
                text = "hidden apps stay installed and searchable by exact name, they just leave the list",
                modifier = Modifier.padding(horizontal = RowInset, vertical = 4.dp),
                style = LocalPlainTypography.current.label,
                color = LocalPlainColors.current.dim,
                maxLines = 3,
            )
        }
        items(apps, key = { "hide-${it.key}" }) { entry ->
            PToggleRow(
                title = labelFor(entry),
                checked = entry.key in settings.hidden,
                onCheckedChange = { hidden -> onSetHidden(entry.key, hidden) },
            )
        }
    }
}

@Composable
private fun WidgetSettings(
    layout: Layout,
    onAddPage: () -> Unit,
    onRenamePage: (String, String) -> Unit,
    onRemovePage: (String) -> Unit,
    onAddWidget: (String) -> Unit,
) {
    var menuPage by remember { mutableStateOf<WidgetPage?>(null) }
    var renamePage by remember { mutableStateOf<WidgetPage?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListEdgePadding) {
        item { PSectionHeader("widget pages") }
        item {
            PText(
                text = "pages sit to the right of the home screen — swipe across to reach them. long-press a widget on its page to resize or remove it.",
                modifier = Modifier.padding(horizontal = RowInset, vertical = 4.dp),
                style = LocalPlainTypography.current.label,
                color = LocalPlainColors.current.dim,
                maxLines = 4,
            )
        }

        if (layout.pages.isEmpty()) {
            item { PEmptyState("no widget pages yet") }
        }

        itemsIndexed(layout.pages, key = { _, page -> "page-${page.id}" }) { index, page ->
            PValueRow(
                title = page.title.ifBlank { "page ${index + 1}" },
                value = "${page.widgets.size} widget${if (page.widgets.size == 1) "" else "s"}",
                onClick = { menuPage = page },
            )
        }

        item { PActionBar(actions = listOf("add page" to onAddPage)) }
    }

    menuPage?.let { page ->
        PMenuDialog(
            title = page.title.ifBlank { "page" },
            onDismiss = { menuPage = null },
            actions = listOf(
                "add widget" to { menuPage = null; onAddWidget(page.id) },
                "rename page" to { menuPage = null; renamePage = page },
                "remove page" to { menuPage = null; onRemovePage(page.id) },
            ),
        )
    }

    renamePage?.let { page ->
        PTextPromptDialog(
            title = "page name",
            initialValue = page.title,
            placeholder = "optional",
            onConfirm = { title -> onRenamePage(page.id, title); renamePage = null },
            onDismiss = { renamePage = null },
        )
    }
}

@Composable
private fun GestureSettings(
    settings: Settings,
    accessibilityEnabled: Boolean,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onSetDefaultLauncher: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListEdgePadding) {
        item { PSectionHeader("gestures") }
        item {
            PText(
                text = "swipe up for apps · swipe left and right for widget pages · long-press for settings",
                modifier = Modifier.padding(horizontal = RowInset, vertical = 4.dp),
                style = LocalPlainTypography.current.label,
                color = LocalPlainColors.current.dim,
                maxLines = 3,
            )
        }
        item {
            PToggleRow(
                title = "double-tap to lock",
                checked = settings.doubleTapToLock,
                onCheckedChange = { value -> onUpdateSettings { it.copy(doubleTapToLock = value) } },
            )
        }
        item {
            PToggleRow(
                title = "swipe down for notifications",
                checked = settings.swipeDownForNotifications,
                onCheckedChange = { value -> onUpdateSettings { it.copy(swipeDownForNotifications = value) } },
            )
        }
        item {
            PValueRow(
                title = "gesture permission",
                value = if (accessibilityEnabled) {
                    "granted"
                } else {
                    "needed for lock and notifications — tap to open accessibility settings"
                },
                onClick = onOpenAccessibilitySettings,
            )
        }

        item { PSectionHeader("system") }
        item {
            PValueRow(
                title = "set as default launcher",
                value = null,
                onClick = onSetDefaultLauncher,
            )
        }
    }
}
