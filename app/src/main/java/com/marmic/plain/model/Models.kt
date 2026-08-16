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

/** One hosted app widget on a widget page. */
@Serializable
data class WidgetSpec(
    val appWidgetId: Int,
    /** Ignored when [fillPage] is set. */
    val heightDp: Int = DEFAULT_HEIGHT_DP,
    /** Give the widget the whole page — this is what makes a month calendar usable. */
    val fillPage: Boolean = false,
) {
    companion object {
        const val DEFAULT_HEIGHT_DP = 260
        const val MIN_HEIGHT_DP = 60
        const val MAX_HEIGHT_DP = 1200
    }
}

/** A swipeable page holding a vertical stack of widgets. */
@Serializable
data class WidgetPage(
    val id: String,
    val title: String = "",
    val widgets: List<WidgetSpec> = emptyList(),
)

@Serializable
data class Layout(
    val pages: List<WidgetPage> = emptyList(),
) {
    val allWidgetIds: List<Int> get() = pages.flatMap { page -> page.widgets.map { it.appWidgetId } }

    companion object {
        val EMPTY = Layout()
    }
}
