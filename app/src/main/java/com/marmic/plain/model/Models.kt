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

/** Pre-stack layouts stored a flat list of these. Kept only so they still load. */
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
 * One band of the home screen, holding one widget or a stack of them.
 *
 * A stack occupies the height of a single widget and is paged through
 * sideways — which is the whole point: a calendar and a task list can share
 * one slot instead of eating the screen one after the other.
 */
@Serializable
data class WidgetSlot(
    val id: String,
    val appWidgetIds: List<Int> = emptyList(),
    val heightDp: Int = WidgetSpec.DEFAULT_HEIGHT_DP,
) {
    val isStack: Boolean get() = appWidgetIds.size > 1
}

/** The home screen: widget slots in order, then the favourite apps. */
@Serializable
data class HomeLayout(
    val slots: List<WidgetSlot> = emptyList(),
    /**
     * Remnant of the pre-stack schema. Folded into [slots] by [normalized] on
     * the way out of storage, so an existing home screen survives the upgrade.
     */
    val widgets: List<WidgetSpec> = emptyList(),
) {
    val widgetIds: List<Int> get() = slots.flatMap { it.appWidgetIds }

    fun slotOf(appWidgetId: Int): WidgetSlot? = slots.firstOrNull { appWidgetId in it.appWidgetIds }

    fun normalized(): HomeLayout =
        if (widgets.isEmpty()) {
            this
        } else {
            copy(
                slots = slots + widgets.map { spec ->
                    WidgetSlot(
                        id = "migrated-${spec.appWidgetId}",
                        appWidgetIds = listOf(spec.appWidgetId),
                        heightDp = spec.heightDp,
                    )
                },
                widgets = emptyList(),
            )
        }

    companion object {
        val EMPTY = HomeLayout()
    }
}
