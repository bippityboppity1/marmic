package com.marmic.plain.ui

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.marmic.plain.model.WidgetPage
import com.marmic.plain.model.WidgetSpec
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.widget.PlainAppWidgetHost
import com.marmic.plain.widget.PlainWidgetHostView

/**
 * One swipeable page holding a vertical stack of hosted widgets.
 *
 * A widget marked [WidgetSpec.fillPage] gets the entire page height — that is
 * the mode to use for a month calendar, which is unusable at gadget size.
 */
@Composable
fun WidgetPageView(
    page: WidgetPage,
    host: PlainAppWidgetHost,
    onAddWidget: () -> Unit,
    onWidgetLongPress: (WidgetSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        val pageWidth = maxWidth

        if (page.widgets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PText(
                    text = "add widget",
                    modifier = Modifier
                        .plainClickable(onClick = onAddWidget)
                        .padding(16.dp),
                    style = type.item,
                    color = colors.dim,
                )
            }
            return@BoxWithConstraints
        }

        // A single fill-page widget must not be inside a scroll container, or it
        // has unbounded height and collapses to its minimum.
        val singleFullPage = page.widgets.size == 1 && page.widgets.first().fillPage

        val content: @Composable () -> Unit = {
            page.widgets.forEach { spec ->
                key(spec.appWidgetId) {
                    val height = if (spec.fillPage) pageHeight else spec.heightDp.dp
                    HostedWidget(
                        spec = spec,
                        host = host,
                        widthDp = pageWidth.value.toInt(),
                        heightDp = height.value.toInt(),
                        onLongPress = { onWidgetLongPress(spec) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height),
                    )
                }
            }
        }

        if (singleFullPage) {
            Column(Modifier.fillMaxSize()) { content() }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun HostedWidget(
    spec: WidgetSpec,
    host: PlainAppWidgetHost,
    widthDp: Int,
    heightDp: Int,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val info = appWidgetManager.getAppWidgetInfo(spec.appWidgetId)

    if (info == null) {
        // The provider was uninstalled, or the binding was revoked.
        Box(modifier, contentAlignment = Alignment.Center) {
            PText(
                text = "widget unavailable — long press to remove",
                modifier = Modifier
                    .plainClickable(onClick = onLongPress, onLongClick = onLongPress)
                    .padding(16.dp),
                style = LocalPlainTypography.current.hint,
                color = LocalPlainColors.current.dim,
                maxLines = 2,
            )
        }
        return
    }

    AndroidView(
        modifier = modifier,
        // createView already binds the id and info; calling setAppWidget again
        // would blank the view back to its default layout.
        factory = { ctx -> host.createView(ctx, spec.appWidgetId, info) },
        update = { view ->
            (view as? PlainWidgetHostView)?.let {
                it.onLongPress = onLongPress
                it.reportSize(widthDp, heightDp)
            }
        },
    )
}
