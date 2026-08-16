package com.marmic.plain.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marmic.plain.model.AppEntry
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.ui.theme.LocalSettings
import kotlinx.coroutines.launch

/** Width reserved down the right edge for the A–Z rail. */
private val RailWidth = 28.dp

/** Apps whose name does not start with a letter are collected under this. */
private const val OTHER_BUCKET = '#'

/**
 * Full-screen, text-only app list.
 *
 * The list runs A→Z from the top with an alphabet rail down the right edge for
 * jumping, and the search bar sits at the bottom where a thumb can reach it.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    onLaunch: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val searchLabel: (AppEntry) -> String = { entry -> settings.renames[entry.key] ?: entry.label }
    val results = remember(apps, query, settings.renames) { rankApps(apps, query, searchLabel) }

    // The rail only makes sense against the full alphabetical list; once the
    // search has narrowed things down there is nothing to jump through.
    val showRail = query.isBlank() && results.size > 12
    val letters = remember(results, showRail) {
        if (!showRail) emptyList() else results.map { bucketOf(searchLabel(it)) }.distinct()
    }

    val activeLetter by remember(results) {
        derivedStateOf {
            results.getOrNull(listState.firstVisibleItemIndex)?.let { bucketOf(searchLabel(it)) }
        }
    }

    // Opening the drawer straight into the keyboard is the fastest path to an
    // app, but it is not everyone's preference.
    LaunchedEffect(Unit) {
        if (settings.searchAutoKeyboard) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    // Jump back to the top whenever the result set changes, otherwise a
    // narrowing search can leave the list scrolled past every match.
    LaunchedEffect(query) {
        if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
    }

    val launch: (AppEntry) -> Unit = { entry ->
        keyboard?.hide()
        onLaunch(entry)
    }

    var lastJumpedTo by remember { mutableStateOf<Char?>(null) }
    val jumpTo: (Char) -> Unit = { letter ->
        if (letter != lastJumpedTo) {
            lastJumpedTo = letter
            val index = results.indexOfFirst { bucketOf(searchLabel(it)) == letter }
            if (index >= 0) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { listState.scrollToItem(index) }
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Box(Modifier.weight(1f)) {
            if (results.isEmpty()) {
                PEmptyState(if (query.isBlank()) "no apps" else "nothing matches “$query”")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = if (showRail) RailWidth else 0.dp),
                    state = listState,
                    contentPadding = ListEdgePadding,
                ) {
                    items(results, key = { it.key }) { entry ->
                        PText(
                            text = labelFor(entry),
                            modifier = Modifier
                                .fillMaxWidth()
                                .plainClickable(
                                    onClick = { launch(entry) },
                                    onLongClick = { onAppLongPress(entry) },
                                )
                                .padding(horizontal = RowInset, vertical = 10.dp),
                            style = type.item,
                        )
                    }
                }
            }

            if (showRail && letters.size > 1) {
                AlphabetRail(
                    letters = letters,
                    activeLetter = activeLetter,
                    onLetter = jumpTo,
                    onRelease = { lastJumpedTo = null },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        PDivider(inset = RowInset)

        BasicTextField(
            value = query,
            onValueChange = { next ->
                query = next
                if (settings.launchOnSingleMatch && next.isNotBlank()) {
                    val matches = rankApps(apps, next, searchLabel)
                    if (matches.size == 1) launch(matches.first())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .padding(horizontal = RowInset, vertical = 16.dp),
            textStyle = type.item.copy(color = colors.foreground),
            singleLine = true,
            cursorBrush = SolidColor(colors.foreground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(launch) }),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        PText("search", style = type.item, color = colors.dim)
                    }
                    inner()
                }
            },
        )

        PActionBar(
            actions = listOf(
                "close" to onClose,
                "clear" to { query = "" },
            ),
        )

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * The A–Z strip. Tapping or sliding a finger down it scrolls the list, one
 * haptic tick per letter crossed.
 */
@Composable
private fun AlphabetRail(
    letters: List<Char>,
    activeLetter: Char?,
    onLetter: (Char) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current

    Column(
        modifier
            .width(RailWidth)
            .fillMaxHeight()
            .pointerInput(letters) {
                detectTapGestures(
                    onPress = { offset ->
                        letterAt(offset.y, size.height, letters)?.let(onLetter)
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                ) { change, _ ->
                    change.consume()
                    letterAt(change.position.y, size.height, letters)?.let(onLetter)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Box(
                Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PText(
                    text = letter.toString(),
                    style = type.label.copy(fontSize = 11.sp),
                    color = if (letter == activeLetter) colors.foreground else colors.dim,
                )
            }
        }
    }
}

private fun letterAt(y: Float, height: Int, letters: List<Char>): Char? {
    if (height <= 0 || letters.isEmpty()) return null
    val index = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
    return letters[index]
}

/** The rail entry an app sorts under. */
private fun bucketOf(label: String): Char {
    val first = label.trimStart().firstOrNull() ?: return OTHER_BUCKET
    return if (first.isLetter()) first.uppercaseChar() else OTHER_BUCKET
}

/**
 * Orders apps by how well they match [query]: exact prefix first, then a match
 * at the start of any word, then initials (e.g. "gc" for "Google Calendar"),
 * then anything containing the query.
 */
fun rankApps(
    apps: List<AppEntry>,
    query: String,
    labelForSearch: (AppEntry) -> String,
): List<AppEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return apps

    val needle = trimmed.lowercase()
    return apps.mapNotNull { entry ->
        val label = labelForSearch(entry).lowercase()
        val rank = when {
            label.startsWith(needle) -> 0
            label.split(' ', '-', '_', '.').any { it.startsWith(needle) } -> 1
            initialsOf(label).startsWith(needle) -> 2
            label.contains(needle) -> 3
            else -> null
        }
        rank?.let { entry to it }
    }.sortedWith(
        compareBy({ it.second }, { labelForSearch(it.first).lowercase() }),
    ).map { it.first }
}

private fun initialsOf(label: String): String =
    label.split(' ', '-', '_', '.')
        .filter { it.isNotEmpty() }
        .map { it.first() }
        .joinToString("")
