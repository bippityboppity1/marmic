package com.marmic.plain.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Clickable with no indication at all. A text-only interface has no surfaces to
 * ripple, so press feedback is applied by the caller as a change in alpha.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.plainClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = source,
        indication = null,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
