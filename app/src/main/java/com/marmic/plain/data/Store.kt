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
import com.marmic.plain.model.WidgetSlot
import com.marmic.plain.model.WidgetSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

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

    val hideStatusBar = booleanPreferencesKey("hide_status_bar")
    val showWallpaper = booleanPreferencesKey("show_wallpaper")
    val wallpaperDim = floatPreferencesKey("wallpaper_dim")
    val widgetTint = stringPreferencesKey("widget_tint")
    val widgetMatchFont = booleanPreferencesKey("widget_match_font")

    val favorites = stringPreferencesKey("favorites")
    val hidden = stringPreferencesKey("hidden")
    val renames = stringPreferencesKey("renames")

    val searchAutoKeyboard = booleanPreferencesKey("search_auto_keyboard")
    val launchOnSingleMatch = booleanPreferencesKey("launch_on_single_match")
    val notificationBadges = booleanPreferencesKey("notification_badges")

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
        hideStatusBar = this[Keys.hideStatusBar] ?: d.hideStatusBar,
        showWallpaper = this[Keys.showWallpaper] ?: d.showWallpaper,
        wallpaperDim = this[Keys.wallpaperDim] ?: d.wallpaperDim,
        widgetTint = this[Keys.widgetTint]?.let { name -> WidgetTint.entries.firstOrNull { it.name == name } } ?: d.widgetTint,
        widgetMatchFont = this[Keys.widgetMatchFont] ?: d.widgetMatchFont,
        favorites = decodeOr(this[Keys.favorites], d.favorites),
        hidden = decodeOr(this[Keys.hidden], d.hidden),
        renames = decodeOr(this[Keys.renames], d.renames),
        searchAutoKeyboard = this[Keys.searchAutoKeyboard] ?: d.searchAutoKeyboard,
        launchOnSingleMatch = this[Keys.launchOnSingleMatch] ?: d.launchOnSingleMatch,
        notificationBadges = this[Keys.notificationBadges] ?: d.notificationBadges,
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
    this[Keys.hideStatusBar] = s.hideStatusBar
    this[Keys.showWallpaper] = s.showWallpaper
    this[Keys.wallpaperDim] = s.wallpaperDim
    this[Keys.widgetTint] = s.widgetTint.name
    this[Keys.widgetMatchFont] = s.widgetMatchFont
    this[Keys.favorites] = json.encodeToString(s.favorites)
    this[Keys.hidden] = json.encodeToString(s.hidden)
    this[Keys.renames] = json.encodeToString(s.renames)
    this[Keys.searchAutoKeyboard] = s.searchAutoKeyboard
    this[Keys.launchOnSingleMatch] = s.launchOnSingleMatch
    this[Keys.notificationBadges] = s.notificationBadges
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

    val layout: Flow<HomeLayout> = context.dataStore.data.map {
        decodeOr(it[Keys.homeLayout], HomeLayout.EMPTY).normalized()
    }

    suspend fun update(transform: (HomeLayout) -> HomeLayout) {
        context.dataStore.edit { prefs ->
            val current = decodeOr(prefs[Keys.homeLayout], HomeLayout.EMPTY).normalized()
            prefs[Keys.homeLayout] = json.encodeToString(transform(current))
        }
    }

    suspend fun current(): HomeLayout = layout.first()

    /** New widgets land in a slot of their own; stacking is an explicit choice. */
    suspend fun addWidget(
        appWidgetId: Int,
        heightDp: Int = WidgetSpec.DEFAULT_HEIGHT_DP,
    ) = update { layout ->
        if (layout.widgetIds.contains(appWidgetId)) {
            layout
        } else {
            val slot = WidgetSlot(
                id = UUID.randomUUID().toString(),
                appWidgetIds = listOf(appWidgetId),
                heightDp = heightDp.coerceIn(WidgetSpec.MIN_HEIGHT_DP, WidgetSpec.MAX_HEIGHT_DP),
            )
            layout.copy(slots = layout.slots + slot)
        }
    }

    suspend fun removeWidget(appWidgetId: Int) = update { layout ->
        layout.copy(
            slots = layout.slots
                .map { it.copy(appWidgetIds = it.appWidgetIds - appWidgetId) }
                .filter { it.appWidgetIds.isNotEmpty() },
        )
    }

    suspend fun setSlotHeight(appWidgetId: Int, heightDp: Int) = update { layout ->
        val clamped = heightDp.coerceIn(WidgetSpec.MIN_HEIGHT_DP, WidgetSpec.MAX_HEIGHT_DP)
        layout.copy(
            slots = layout.slots.map {
                if (appWidgetId in it.appWidgetIds) it.copy(heightDp = clamped) else it
            },
        )
    }

    /** Moves the slot holding [appWidgetId] up (-1) or down (+1). */
    suspend fun moveSlot(appWidgetId: Int, delta: Int) = update { layout ->
        val slots = layout.slots.toMutableList()
        val from = slots.indexOfFirst { appWidgetId in it.appWidgetIds }
        val to = from + delta
        if (from < 0 || to !in slots.indices) return@update layout
        slots.add(to, slots.removeAt(from))
        layout.copy(slots = slots)
    }

    /** Folds this widget's slot into the one above it, making a stack. */
    suspend fun stackWithPrevious(appWidgetId: Int) = update { layout ->
        val slots = layout.slots.toMutableList()
        val index = slots.indexOfFirst { appWidgetId in it.appWidgetIds }
        if (index <= 0) return@update layout

        val moving = slots.removeAt(index)
        val target = slots[index - 1]
        slots[index - 1] = target.copy(appWidgetIds = target.appWidgetIds + moving.appWidgetIds)
        layout.copy(slots = slots)
    }

    /** Pulls one widget out of its stack into a slot of its own, just below. */
    suspend fun unstack(appWidgetId: Int) = update { layout ->
        val slots = layout.slots.toMutableList()
        val index = slots.indexOfFirst { appWidgetId in it.appWidgetIds }
        if (index < 0) return@update layout

        val slot = slots[index]
        if (!slot.isStack) return@update layout

        slots[index] = slot.copy(appWidgetIds = slot.appWidgetIds - appWidgetId)
        slots.add(
            index + 1,
            WidgetSlot(
                id = UUID.randomUUID().toString(),
                appWidgetIds = listOf(appWidgetId),
                heightDp = slot.heightDp,
            ),
        )
        layout.copy(slots = slots)
    }
}
