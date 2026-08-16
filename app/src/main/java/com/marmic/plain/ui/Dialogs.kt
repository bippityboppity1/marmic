package com.marmic.plain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography

/** A menu that is simply a heading and a column of words. */
@Composable
fun PMenuDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(vertical = 20.dp),
        ) {
            PText(
                text = title,
                modifier = Modifier.padding(horizontal = RowInset, vertical = 4.dp),
                style = type.label,
                color = colors.dim,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            actions.forEach { (label, action) ->
                PRow(onClick = action) {
                    PText(label, modifier = Modifier.weight(1f), style = type.item)
                }
            }
        }
    }
}

/** Single-field text prompt, used for renaming apps and titling widget pages. */
@Composable
fun PTextPromptDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    var value by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(horizontal = RowInset, vertical = 24.dp),
        ) {
            PText(title, style = type.label, color = colors.dim, maxLines = 2)
            Spacer(Modifier.height(12.dp))

            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = type.item.copy(color = colors.foreground),
                singleLine = true,
                cursorBrush = SolidColor(colors.foreground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value) }),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) PText(placeholder, style = type.item, color = colors.dim)
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            PActionBar(
                actions = buildList<Pair<String, () -> Unit>> {
                    add("save" to { onConfirm(value) })
                    addAll(extraActions)
                    add("cancel" to onDismiss)
                },
                horizontalPadding = 0.dp,
            )
        }
    }
}
