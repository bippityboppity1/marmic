package com.marmic.plain.ui

import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.marmic.plain.model.AppEntry
import com.marmic.plain.model.HomeLayout
import com.marmic.plain.model.WidgetSpec
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.ui.theme.LocalSettings
import com.marmic.plain.widget.PlainAppWidgetHost
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The whole launcher, on one screen: clock, then widgets in order, then the
 * favourite apps.
 *
 * It scrolls, so the calendar can be a real month grid rather than something
 * squeezed to fit. Dragging past either end of the scroll is what opens the
 * drawer and the notification shade — which means the gesture works whether or
 * not the content happens to be taller than the screen.
 */
@Composable
fun HomePage(
    layout: HomeLayout,
    host: PlainAppWidgetHost,
    favorites: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    onLaunch: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwipeDown: () -> Unit,
    onDoubleTap: () -> Unit,
    onAddWidget: () -> Unit,
    onWidgetLongPress: (WidgetSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    val scrollState = rememberScrollState()

    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val edgeDrag = remember(threshold, onOpenDrawer, onSwipeDown) {
        EdgeDragConnection(threshold, onSwipeUp = onOpenDrawer, onSwipeDown = onSwipeDown)
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .nestedScroll(edgeDrag)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onOpenSettings() },
                    onDoubleTap = { onDoubleTap() },
                )
            },
    ) {
        val widthDp = maxWidth.value.toInt()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Spacer(Modifier.height(24.dp))

            Column(Modifier.padding(horizontal = RowInset)) {
                ClockBlock()
            }

            // Widgets run edge to edge: a month grid wants every pixel of width.
            layout.widgets.forEach { spec ->
                key(spec.appWidgetId) {
                    Spacer(Modifier.height(12.dp))
                    HostedWidget(
                        spec = spec,
                        host = host,
                        widthDp = widthDp,
                        heightDp = spec.heightDp,
                        onLongPress = { onWidgetLongPress(spec) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(spec.heightDp.dp),
                    )
                }
            }

            if (layout.widgets.isEmpty()) {
                PText(
                    text = "add widget",
                    modifier = Modifier
                        .padding(horizontal = RowInset)
                        .plainClickable(onClick = onAddWidget)
                        .padding(vertical = 14.dp),
                    style = type.hint,
                    color = colors.dim,
                )
            }

            Spacer(Modifier.height(20.dp))

            Column(
                Modifier.padding(horizontal = RowInset),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                favorites.forEach { entry ->
                    PText(
                        text = labelFor(entry),
                        modifier = Modifier
                            .fillMaxWidth()
                            .plainClickable(
                                onClick = { onLaunch(entry) },
                                onLongClick = { onAppLongPress(entry) },
                            )
                            .padding(vertical = 9.dp),
                        style = type.item,
                    )
                }
            }

            PText(
                text = if (favorites.isEmpty()) "swipe up for apps" else "apps",
                modifier = Modifier
                    .padding(horizontal = RowInset)
                    .plainClickable(onClick = onOpenDrawer)
                    .padding(top = 18.dp, bottom = 6.dp),
                style = type.label,
                color = colors.dim,
            )

            Spacer(Modifier.height(28.dp))
        }

        if (settings.showBattery) {
            BatteryLabel(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = RowInset, vertical = 30.dp),
            )
        }
    }
}

/**
 * Turns a drag past either end of the scroll into a gesture.
 *
 * The scrolling child gets first refusal on every drag, so this only ever sees
 * what is left over: dragging up at the bottom, or down at the top. When the
 * content is short enough not to scroll at all, everything is left over, and
 * the gestures behave exactly as they did before there were widgets.
 */
private class EdgeDragConnection(
    private val thresholdPx: Float,
    private val onSwipeUp: () -> Unit,
    private val onSwipeDown: () -> Unit,
) : NestedScrollConnection {

    private var accumulated = 0f

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (available.y == 0f) return Offset.Zero

        // A change of direction starts the measurement again, so a wobble
        // part-way through a drag cannot add up to a gesture.
        if (accumulated != 0f && (accumulated < 0f) != (available.y < 0f)) accumulated = 0f
        accumulated += available.y

        when {
            accumulated <= -thresholdPx -> {
                accumulated = 0f
                onSwipeUp()
            }

            accumulated >= thresholdPx -> {
                accumulated = 0f
                onSwipeDown()
            }
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        accumulated = 0f
        return Velocity.Zero
    }
}

@Composable
private fun ClockBlock() {
    val settings = LocalSettings.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    val now by rememberMinuteTick()

    if (settings.clockSize.sp > 0) {
        val pattern = if (settings.clock24h) "HH:mm" else "h:mm a"
        PText(
            text = now.format(DateTimeFormatter.ofPattern(pattern)),
            style = type.clock,
            transformCase = settings.clock24h.not(),
        )
    }
    if (settings.showDate) {
        PText(
            text = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
            style = type.date,
            color = colors.dim,
        )
    }
}

@Composable
private fun BatteryLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    val now by rememberMinuteTick()

    var level by remember { mutableStateOf(-1) }
    LaunchedEffect(now) {
        level = runCatching {
            context.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
    }

    if (level in 0..100) {
        PText("$level%", modifier = modifier, style = type.label, color = colors.dim)
    }
}

/** Recomposes once per wall-clock minute, on the minute. */
@Composable
fun rememberMinuteTick(): State<LocalDateTime> {
    val state = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.value = LocalDateTime.now()
            // Sleep to the top of the next minute rather than a fixed 60s, so
            // the display never sits a whole minute behind.
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    return state
}
