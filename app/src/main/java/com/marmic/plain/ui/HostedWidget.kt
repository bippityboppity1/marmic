package com.marmic.plain.ui

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.marmic.plain.model.WidgetSpec
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.widget.PlainAppWidgetHost
import com.marmic.plain.widget.PlainWidgetHostView

/**
 * One hosted app widget. The caller sizes it; we pass that size on to the
 * provider so it lays out for the room it really has.
 */
@Composable
fun HostedWidget(
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
                text = "widget unavailable — tap to remove",
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
