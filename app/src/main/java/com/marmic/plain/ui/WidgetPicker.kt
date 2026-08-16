package com.marmic.plain.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A provider plus the text we need to show it without loading any preview image. */
data class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val widgetLabel: String,
    val minWidthDp: Int,
    val minHeightDp: Int,
) {
    val key: String get() = "${info.provider.flattenToString()}|$widgetLabel"
}

/**
 * The system widget picker is a wall of preview images. This is the same list
 * as words: app name, widget name, and the size it asks for.
 */
@Composable
fun WidgetPicker(
    onPick: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current

    val choices by produceState(initialValue = emptyList<WidgetChoice>(), context) {
        value = withContext(Dispatchers.IO) { loadWidgetChoices(context) }
    }

    Column(modifier.fillMaxSize()) {
        PSectionHeader("add widget")

        if (choices.isEmpty()) {
            PEmptyState("no widgets available")
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = ListEdgePadding,
            ) {
                items(choices, key = { it.key }) { choice ->
                    PValueRow(
                        title = choice.widgetLabel,
                        value = "${choice.appLabel} · ${choice.minWidthDp}×${choice.minHeightDp} dp",
                        onClick = { onPick(choice.info) },
                    )
                }
            }
        }

        PText(
            text = "widgets keep their own colours — they follow the system light or dark theme, not the palette above",
            modifier = Modifier.padding(horizontal = RowInset, vertical = 8.dp),
            style = type.label,
            color = colors.dim,
            maxLines = 3,
        )

        PActionBar(actions = listOf("back" to onClose))
    }
}

private fun loadWidgetChoices(context: Context): List<WidgetChoice> {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val packageManager = context.packageManager
    val density = context.resources.displayMetrics

    val providers = runCatching { appWidgetManager.installedProviders }.getOrDefault(emptyList())

    return providers.mapNotNull { info ->
        val widgetLabel = runCatching { info.loadLabel(packageManager) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(info.provider.packageName, 0),
            ).toString()
        }.getOrDefault(info.provider.packageName)

        WidgetChoice(
            info = info,
            appLabel = appLabel,
            widgetLabel = widgetLabel,
            minWidthDp = pxToDp(info.minWidth, density.density),
            minHeightDp = pxToDp(info.minHeight, density.density),
        )
    }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, { it.appLabel })
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.widgetLabel },
    )
}

private fun pxToDp(px: Int, density: Float): Int =
    if (density <= 0f) px else (px / density).roundToInt()
