package com.marmic.plain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marmic.plain.ui.theme.LocalPlainColors
import com.marmic.plain.ui.theme.LocalPlainTypography
import com.marmic.plain.ui.theme.cased

/** Standard horizontal inset for every list row in the app. */
val RowInset = 28.dp

/** Breathing room at the top and bottom of scrolling lists. */
val ListEdgePadding = PaddingValues(vertical = 8.dp)

/**
 * Every piece of text in the launcher goes through here, so the lowercase
 * preference and the palette are impossible to forget.
 */
@Composable
fun PText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalPlainTypography.current.item,
    color: Color = LocalPlainColors.current.foreground,
    maxLines: Int = 1,
    transformCase: Boolean = true,
) {
    BasicText(
        text = if (transformCase) text.cased() else text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A tappable row with no ripple — press feedback is a simple dim, which is the
 * only kind of feedback that suits a text-only interface.
 */
@Composable
fun PRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    verticalPadding: Dp = 12.dp,
    horizontalPadding: Dp = RowInset,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick, onLongClick = onLongClick, interactionSource = interaction)
            .alpha(if (pressed) 0.45f else 1f)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun PDivider(modifier: Modifier = Modifier, inset: Dp = RowInset) {
    Spacer(
        modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPlainColors.current.divider),
    )
}

/** A settings row showing a name and, underneath, its current value. */
@Composable
fun PValueRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    PRow(onClick = onClick, onLongClick = onLongClick, modifier = modifier) {
        Column(Modifier.weight(1f)) {
            PText(title, style = type.item)
            if (value != null) {
                PText(value, style = type.label, color = colors.dim, maxLines = 2)
            }
        }
    }
}

/** Boolean setting. The state is spelled out in words rather than drawn as a switch. */
@Composable
fun PToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .alpha(if (pressed) 0.45f else 1f)
            .padding(horizontal = RowInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PText(title, style = type.item)
            if (subtitle != null) PText(subtitle, style = type.label, color = colors.dim, maxLines = 3)
        }
        Spacer(Modifier.width(16.dp))
        PText(
            text = if (checked) "on" else "off",
            style = type.label,
            color = if (checked) colors.foreground else colors.dim,
        )
    }
}

/** Section heading in a settings or picker list. */
@Composable
fun PSectionHeader(title: String, modifier: Modifier = Modifier) {
    PText(
        text = title,
        modifier = modifier.padding(start = RowInset, end = RowInset, top = 28.dp, bottom = 8.dp),
        style = LocalPlainTypography.current.label,
        color = LocalPlainColors.current.dim,
    )
}

/** Centred placeholder for empty lists. */
@Composable
fun PEmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        PText(
            text = text,
            style = LocalPlainTypography.current.hint,
            color = LocalPlainColors.current.dim,
            maxLines = 4,
        )
    }
}

/** Horizontal choice list, rendered as words rather than radio buttons. */
@Composable
fun <T> POptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = RowInset, vertical = 12.dp),
    ) {
        PText(title, style = type.item)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                PText(
                    text = label(option),
                    modifier = Modifier
                        .plainClickable(onClick = { onSelect(option) })
                        .padding(vertical = 4.dp),
                    style = type.hint,
                    color = if (isSelected) colors.foreground else colors.dim,
                )
            }
        }
    }
}

/** A row of words used as a bottom action bar (back / add / done). */
@Composable
fun PActionBar(
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = RowInset,
) {
    val colors = LocalPlainColors.current
    val type = LocalPlainTypography.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        actions.forEach { (label, action) ->
            PText(
                text = label,
                modifier = Modifier.plainClickable(onClick = action),
                style = type.hint,
                color = colors.dim,
            )
        }
    }
}
