package com.marmic.plain.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.marmic.plain.model.AppEntry
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.ui.theme.LocalSettings

/**
 * Full-screen, text-only app list with the search field at the bottom, where a
 * thumb can reach it.
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

    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val results = remember(apps, query, settings.renames) {
        rankApps(apps, query, labelForSearch = { entry -> settings.renames[entry.key] ?: entry.label })
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
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
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
        }

        Spacer(Modifier.height(4.dp))

        BasicTextField(
            value = query,
            onValueChange = { next ->
                query = next
                if (settings.launchOnSingleMatch) {
                    val matches = rankApps(apps, next) { entry -> settings.renames[entry.key] ?: entry.label }
                    if (next.isNotBlank() && matches.size == 1) launch(matches.first())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .padding(horizontal = RowInset, vertical = 14.dp),
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
    }
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
