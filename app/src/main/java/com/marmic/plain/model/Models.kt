package com.marmic.plain.model

import android.content.ComponentName
import android.os.UserHandle
import kotlinx.serialization.Serializable

/** A launchable activity, as shown in the drawer. */
data class AppEntry(
    val componentName: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
) {
    val packageName: String get() = componentName.packageName

    /**
     * Stable identity used everywhere we persist a reference to an app.
     * Includes the user serial so a work-profile clone of an app is distinct
     * from the personal one.
     */
    val key: String get() = key(userSerial, componentName)

    companion object {
        fun key(userSerial: Long, component: ComponentName): String =
            "$userSerial|${component.flattenToString()}"
    }
}

/** One hosted app widget, stacked on the home screen. */
@Serializable
data class WidgetSpec(
    val appWidgetId: Int,
    val heightDp: Int = DEFAULT_HEIGHT_DP,
) {
    companion object {
        const val DEFAULT_HEIGHT_DP = 300
        const val MIN_HEIGHT_DP = 80
        const val MAX_HEIGHT_DP = 900

        /** Offered in the widget's long-press menu. A month grid wants ~360. */
        val HEIGHT_PRESETS = listOf(120, 180, 240, 300, 360, 440)
    }
}

/**
 * The home screen: widgets first, in order, then the favourite apps.
 *
 * There are no widget pages — everything lives on one scrolling home screen,
 * so a month calendar and a task list sit above the app list rather than a
 * swipe away.
 */
@Serializable
data class HomeLayout(
    val widgets: List<WidgetSpec> = emptyList(),
) {
    val widgetIds: List<Int> get() = widgets.map { it.appWidgetId }

    companion object {
        val EMPTY = HomeLayout()
    }
}
