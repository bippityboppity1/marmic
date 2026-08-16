package com.marmic.plain.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.marmic.plain.model.HomeLayout
import com.marmic.plain.model.WidgetSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "plain")

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private object Keys {
    val palette = stringPreferencesKey("palette")
    val typeface = stringPreferencesKey("typeface")
    val textScale = floatPreferencesKey("text_scale")
    val lowercase = booleanPreferencesKey("lowercase")

    val clockSize = stringPreferencesKey("clock_size")
    val clock24h = booleanPreferencesKey("clock_24h")
    val showDate = booleanPreferencesKey("show_date")
    val showBattery = booleanPreferencesKey("show_battery")

    val showWallpaper = booleanPreferencesKey("show_wallpaper")
    val wallpaperDim = floatPreferencesKey("wallpaper_dim")

    val favorites = stringPreferencesKey("favorites")
    val hidden = stringPreferencesKey("hidden")
    val renames = stringPreferencesKey("renames")

    val searchAutoKeyboard = booleanPreferencesKey("search_auto_keyboard")
    val launchOnSingleMatch = booleanPreferencesKey("launch_on_single_match")

    val doubleTapToLock = booleanPreferencesKey("double_tap_to_lock")
    val swipeDownForNotifications = booleanPreferencesKey("swipe_down_notifications")

    val homeLayout = stringPreferencesKey("home_layout")
}

/** Decodes JSON, falling back to [fallback] rather than wiping the user's setup. */
private inline fun <reified T> decodeOr(raw: String?, fallback: T): T =
    if (raw.isNullOrBlank()) fallback else runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)

private fun Preferences.toSettings(): Settings {
    val d = Settings.DEFAULT
    return Settings(
        palette = this[Keys.palette]?.let { name -> Palette.entries.firstOrNull { it.name == name } } ?: d.palette,
        typeface = this[Keys.typeface]?.let { name -> Typeface.entries.firstOrNull { it.name == name } } ?: d.typeface,
        textScale = this[Keys.textScale] ?: d.textScale,
        lowercase = this[Keys.lowercase] ?: d.lowercase,
        clockSize = this[Keys.clockSize]?.let { name -> ClockSize.entries.firstOrNull { it.name == name } } ?: d.clockSize,
        clock24h = this[Keys.clock24h] ?: d.clock24h,
        showDate = this[Keys.showDate] ?: d.showDate,
        showBattery = this[Keys.showBattery] ?: d.showBattery,
        showWallpaper = this[Keys.showWallpaper] ?: d.showWallpaper,
        wallpaperDim = this[Keys.wallpaperDim] ?: d.wallpaperDim,
        favorites = decodeOr(this[Keys.favorites], d.favorites),
        hidden = decodeOr(this[Keys.hidden], d.hidden),
        renames = decodeOr(this[Keys.renames], d.renames),
        searchAutoKeyboard = this[Keys.searchAutoKeyboard] ?: d.searchAutoKeyboard,
        launchOnSingleMatch = this[Keys.launchOnSingleMatch] ?: d.launchOnSingleMatch,
        doubleTapToLock = this[Keys.doubleTapToLock] ?: d.doubleTapToLock,
        swipeDownForNotifications = this[Keys.swipeDownForNotifications] ?: d.swipeDownForNotifications,
    )
}

private fun MutablePreferences.write(s: Settings) {
    this[Keys.palette] = s.palette.name
    this[Keys.typeface] = s.typeface.name
    this[Keys.textScale] = s.textScale
    this[Keys.lowercase] = s.lowercase
    this[Keys.clockSize] = s.clockSize.name
    this[Keys.clock24h] = s.clock24h
    this[Keys.showDate] = s.showDate
    this[Keys.showBattery] = s.showBattery
    this[Keys.showWallpaper] = s.showWallpaper
    this[Keys.wallpaperDim] = s.wallpaperDim
    this[Keys.favorites] = json.encodeToString(s.favorites)
    this[Keys.hidden] = json.encodeToString(s.hidden)
    this[Keys.renames] = json.encodeToString(s.renames)
    this[Keys.searchAutoKeyboard] = s.searchAutoKeyboard
    this[Keys.launchOnSingleMatch] = s.launchOnSingleMatch
    this[Keys.doubleTapToLock] = s.doubleTapToLock
    this[Keys.swipeDownForNotifications] = s.swipeDownForNotifications
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    suspend fun toggleFavorite(key: String) = update { current ->
        val favorites = when {
            key in current.favorites -> current.favorites - key
            current.favorites.size >= Settings.MAX_FAVORITES -> current.favorites
            else -> current.favorites + key
        }
        current.copy(favorites = favorites)
    }

    suspend fun moveFavorite(key: String, delta: Int) = update { current ->
        val list = current.favorites.toMutableList()
        val from = list.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in list.indices) return@update current
        list.add(to, list.removeAt(from))
        current.copy(favorites = list)
    }

    suspend fun setHidden(key: String, hidden: Boolean) = update { current ->
        current.copy(
            hidden = if (hidden) current.hidden + key else current.hidden - key,
            // A hidden app has no business sitting on the home screen.
            favorites = if (hidden) current.favorites - key else current.favorites,
        )
    }

    suspend fun rename(key: String, label: String?) = update { current ->
        val trimmed = label?.trim().orEmpty()
        current.copy(
            renames = if (trimmed.isEmpty()) current.renames - key else current.renames + (key to trimmed),
        )
    }
}

class LayoutRepository(private val context: Context) {

    val layout: Flow<HomeLayout> =
        context.dataStore.data.map { decodeOr(it[Keys.homeLayout], HomeLayout.EMPTY) }

    suspend fun update(transform: (HomeLayout) -> HomeLayout) {
        context.dataStore.edit { prefs ->
            val current = decodeOr(prefs[Keys.homeLayout], HomeLayout.EMPTY)
            prefs[Keys.homeLayout] = json.encodeToString(transform(current))
        }
    }

    /** Reads the layout once, outside of composition. */
    suspend fun current(): HomeLayout = layout.first()

    suspend fun addWidget(appWidgetId: Int) = update { layout ->
        if (layout.widgets.any { it.appWidgetId == appWidgetId }) {
            layout
        } else {
            layout.copy(widgets = layout.widgets + WidgetSpec(appWidgetId = appWidgetId))
        }
    }

    suspend fun removeWidget(appWidgetId: Int) = update { layout ->
        layout.copy(widgets = layout.widgets.filterNot { it.appWidgetId == appWidgetId })
    }

    suspend fun setWidgetHeight(appWidgetId: Int, heightDp: Int) = update { layout ->
        val clamped = heightDp.coerceIn(WidgetSpec.MIN_HEIGHT_DP, WidgetSpec.MAX_HEIGHT_DP)
        layout.copy(
            widgets = layout.widgets.map {
                if (it.appWidgetId == appWidgetId) it.copy(heightDp = clamped) else it
            },
        )
    }

    /** Moves a widget up (-1) or down (+1) the home stack. */
    suspend fun moveWidget(appWidgetId: Int, delta: Int) = update { layout ->
        val widgets = layout.widgets.toMutableList()
        val from = widgets.indexOfFirst { it.appWidgetId == appWidgetId }
        val to = from + delta
        if (from < 0 || to !in widgets.indices) return@update layout
        widgets.add(to, widgets.removeAt(from))
        layout.copy(widgets = widgets)
    }
}
