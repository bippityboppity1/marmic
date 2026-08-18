package com.marmic.plain.ui

import android.appwidget.AppWidgetManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface as AndroidTypeface
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.marmic.plain.data.Typeface as PlainTypeface
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.widget.PlainAppWidgetHost
import com.marmic.plain.widget.PlainWidgetHostView

/**
 * One hosted app widget. The caller sizes it; we pass that size on to the
 * provider so it lays out for the room it really has.
 *
 * When [duotone] is set the whole view is composited through a colour filter
 * that maps its brightness onto the palette's background-to-foreground ramp.
 * That is the only lever available — the widget's pixels come from another
 * process as RemoteViews, so its own colours cannot be edited, but what we
 * composite them through is ours.
 */
@Composable
fun HostedWidget(
    appWidgetId: Int,
    host: PlainAppWidgetHost,
    widthDp: Int,
    heightDp: Int,
    duotone: Pair<Color, Color>?,
    typeface: AndroidTypeface?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val info = appWidgetManager.getAppWidgetInfo(appWidgetId)

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
        factory = { ctx -> host.createView(ctx, appWidgetId, info) },
        update = { view ->
            val hostView = view as? PlainWidgetHostView
            hostView?.let {
                it.onLongPress = onLongPress
                it.typeface = typeface
                it.reportSize(widthDp, heightDp)
            }

            val wanted = duotone?.let { it.first.toArgb() to it.second.toArgb() }
            if (hostView == null || hostView.appliedDuotone != wanted) {
                if (duotone == null) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                } else {
                    val paint = Paint().apply {
                        colorFilter = ColorMatrixColorFilter(duotoneMatrix(duotone.first, duotone.second))
                    }
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                }
                hostView?.appliedDuotone = wanted
            }
        },
    )
}

/**
 * Builds a colour matrix that collapses the source to luminance and then spans
 * it across [shadow] to [highlight]. Alpha passes through untouched, so a
 * widget with a transparent background still has one.
 */
private fun duotoneMatrix(shadow: Color, highlight: Color): ColorMatrix {
    // Rec. 709 luminance weights — the same ones the eye-friendly greyscale
    // conversions use, so text keeps its contrast against its own background.
    val lr = 0.2126f
    val lg = 0.7152f
    val lb = 0.0722f

    val low = floatArrayOf(shadow.red, shadow.green, shadow.blue)
    val high = floatArrayOf(highlight.red, highlight.green, highlight.blue)

    val m = FloatArray(20)
    for (channel in 0..2) {
        val span = high[channel] - low[channel]
        val row = channel * 5
        m[row] = lr * span
        m[row + 1] = lg * span
        m[row + 2] = lb * span
        m[row + 3] = 0f
        m[row + 4] = low[channel] * 255f
    }
    // Alpha row, untouched.
    m[18] = 1f

    return ColorMatrix(m)
}

/** The system font behind each of the launcher's typeface choices. */
fun androidTypefaceFor(typeface: PlainTypeface): AndroidTypeface = when (typeface) {
    PlainTypeface.SANS -> AndroidTypeface.SANS_SERIF
    PlainTypeface.SERIF -> AndroidTypeface.SERIF
    PlainTypeface.MONO -> AndroidTypeface.MONOSPACE
}
