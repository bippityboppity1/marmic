package com.marmic.plain.ui

import android.graphics.Typeface as AndroidTypeface
import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marmic.plain.model.AppEntry
import com.marmic.plain.model.HomeLayout
import com.marmic.plain.model.WidgetSlot
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.ui.theme.LocalSettings
import com.marmic.plain.widget.PlainAppWidgetHost
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Distance a vertical drag must cover before it counts as a swipe. */
private const val SWIPE_THRESHOLD_PX = 90f

/**
 * The whole launcher on one screen, and only one screen: it never scrolls.
 *
 * The clock takes what it needs at the top and the apps take what they need at
 * the bottom; the widgets divide up everything left over, in proportion to
 * their configured heights. That is what keeps the app list visible no matter
 * how tall a widget asked to be — and it is why stacking exists, since two
 * widgets sharing one slot cost the same room as one.
 */
@Composable
fun HomePage(
    layout: HomeLayout,
    host: PlainAppWidgetHost,
    favorites: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    badgeFor: (AppEntry) -> Int,
    duotone: Pair<Color, Color>?,
    widgetTypeface: AndroidTypeface?,
    onLaunch: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwipeDown: () -> Unit,
    onDoubleTap: () -> Unit,
    onAddWidget: () -> Unit,
    onWidgetLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current

    // Deliberately NOT on the root: a drag that starts over a widget belongs to
    // the widget. Claiming it here is what stopped a task list from scrolling.
    val gestures = Modifier.homeGestures(
        onOpenDrawer = onOpenDrawer,
        onOpenSettings = onOpenSettings,
        onSwipeDown = onSwipeDown,
        onDoubleTap = onDoubleTap,
    )

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(gestures)
                    .padding(horizontal = RowInset),
            ) {
                ClockBlock()
            }

            Spacer(Modifier.height(12.dp))

            // Widgets absorb the slack, so the apps below never get pushed off.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (layout.slots.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PText(
                            text = "add widget",
                            modifier = Modifier
                                .plainClickable(onClick = onAddWidget)
                                .padding(16.dp),
                            style = type.hint,
                            color = colors.dim,
                        )
                    }
                } else {
                    layout.slots.forEach { slot ->
                        key(slot.id) {
                            WidgetSlotView(
                                slot = slot,
                                host = host,
                                duotone = duotone,
                                typeface = widgetTypeface,
                                onWidgetLongPress = onWidgetLongPress,
                                modifier = Modifier
                                    .weight(slot.heightDp.toFloat())
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .then(gestures)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                favorites.forEach { entry ->
                    AppRow(
                        label = labelFor(entry),
                        badgeCount = badgeFor(entry),
                        onClick = { onLaunch(entry) },
                        onLongClick = { onAppLongPress(entry) },
                        modifier = Modifier.padding(horizontal = RowInset, vertical = 7.dp),
                        style = type.item,
                    )
                }

                PText(
                    text = if (favorites.isEmpty()) "swipe up for apps" else "apps",
                    modifier = Modifier
                        .padding(horizontal = RowInset)
                        .plainClickable(onClick = onOpenDrawer)
                        .padding(top = 14.dp, bottom = 2.dp),
                    style = type.label,
                    color = colors.dim,
                )
            }
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
 * The home-screen gestures, applied only to the strips that are ours: the clock
 * at the top and the app list at the bottom.
 *
 * Widgets are left alone on purpose. A hosted widget can contain a scrolling
 * list of its own, and a drag detector stretched across the whole screen wins
 * that gesture before the list ever sees it — which is exactly how a task list
 * stops scrolling.
 *
 * The callbacks are read through rememberUpdatedState so the detectors, which
 * are keyed on Unit and therefore never restart, always call the current ones
 * rather than whichever were captured on first composition.
 */
@Composable
private fun Modifier.homeGestures(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwipeDown: () -> Unit,
    onDoubleTap: () -> Unit,
): Modifier {
    val openDrawer by rememberUpdatedState(onOpenDrawer)
    val openSettings by rememberUpdatedState(onOpenSettings)
    val swipeDown by rememberUpdatedState(onSwipeDown)
    val doubleTap by rememberUpdatedState(onDoubleTap)

    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { openSettings() },
                onDoubleTap = { doubleTap() },
            )
        }
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    when {
                        total <= -SWIPE_THRESHOLD_PX -> openDrawer()
                        total >= SWIPE_THRESHOLD_PX -> swipeDown()
                    }
                },
                onDragCancel = { total = 0f },
            ) { _, dragAmount -> total += dragAmount }
        }
}

/**
 * One band of the home screen. A single widget fills it; a stack pages through
 * it sideways with a row of marks underneath to say where you are.
 */
@Composable
private fun WidgetSlotView(
    slot: WidgetSlot,
    host: PlainAppWidgetHost,
    duotone: Pair<Color, Color>?,
    typeface: AndroidTypeface?,
    onWidgetLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value.toInt()
        val heightDp = maxHeight.value.toInt()

        if (!slot.isStack) {
            val id = slot.appWidgetIds.firstOrNull() ?: return@BoxWithConstraints
            HostedWidget(
                appWidgetId = id,
                host = host,
                widthDp = widthDp,
                heightDp = heightDp,
                duotone = duotone,
                typeface = typeface,
                onLongPress = { onWidgetLongPress(id) },
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }

        val pagerState = rememberPagerState(pageCount = { slot.appWidgetIds.size })

        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                val id = slot.appWidgetIds[page]
                HostedWidget(
                    appWidgetId = id,
                    host = host,
                    widthDp = widthDp,
                    heightDp = heightDp,
                    duotone = duotone,
                    typeface = typeface,
                    onLongPress = { onWidgetLongPress(id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            StackMarks(
                count = slot.appWidgetIds.size,
                current = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Where you are in a stack, drawn as text rather than as dots. */
@Composable
private fun StackMarks(count: Int, current: Int, modifier: Modifier = Modifier) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    Row(
        modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            PText(
                text = if (index == current) "—" else "·",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = type.label,
                color = if (index == current) colors.foreground else colors.dim,
                transformCase = false,
            )
        }
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
