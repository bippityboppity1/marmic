package com.marmic.plain.ui

import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marmic.plain.model.AppEntry
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.ui.theme.LocalSettings
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Distance a vertical drag must cover before it counts as a swipe. */
private const val SWIPE_THRESHOLD_PX = 90f

@Composable
fun HomePage(
    favorites: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    onLaunch: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwipeDown: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current

    Box(
        modifier
            .fillMaxSize()
            // Taps: long-press opens settings, double-tap locks the screen.
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onOpenSettings() },
                    onDoubleTap = { onDoubleTap() },
                )
            }
            // Drags: up opens the drawer, down pulls the notification shade.
            // Only the home page claims vertical drags; widget pages leave them
            // to the widgets themselves.
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total <= -SWIPE_THRESHOLD_PX -> onOpenDrawer()
                            total >= SWIPE_THRESHOLD_PX -> onSwipeDown()
                        }
                    },
                    onDragCancel = { total = 0f },
                ) { _, dragAmount -> total += dragAmount }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = RowInset, vertical = 24.dp),
        ) {
            ClockBlock()

            Spacer(Modifier.weight(1f))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

            Spacer(Modifier.height(20.dp))

            PText(
                text = if (favorites.isEmpty()) "swipe up for apps" else "apps",
                modifier = Modifier
                    .plainClickable(onClick = onOpenDrawer)
                    .padding(vertical = 6.dp),
                style = type.label,
                color = colors.dim,
            )
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
