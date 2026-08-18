package com.marmic.plain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marmic.plain.ui.theme.LocalPlainTypography

/** The one deliberately non-text element in the launcher. */
private val BadgeRed = Color(0xFFE5484D)

/**
 * Unread count for an app, as a red dot with the number in it.
 *
 * Sized off the text rather than fixed, so a three-digit count still fits
 * instead of spilling out of the circle.
 */
@Composable
fun NotificationBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return

    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .height(18.dp)
            .background(BadgeRed, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * An app as a row of text, with its notification badge to the left of the name.
 *
 * Shared by the home screen and the drawer so a badge looks identical in both.
 */
@Composable
fun AppRow(
    label: String,
    badgeCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalPlainTypography.current.item,
) {
    Row(
        modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badgeCount > 0) {
            NotificationBadge(badgeCount)
            Spacer(Modifier.width(10.dp))
        }
        PText(text = label, style = style)
    }
}
