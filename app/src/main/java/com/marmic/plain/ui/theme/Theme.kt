package com.marmic.plain.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.marmic.plain.data.Palette
import com.marmic.plain.data.Settings
import com.marmic.plain.data.Typeface

@Immutable
data class PlainColors(
    val background: Color,
    val foreground: Color,
    /** Secondary text: hints, counts, section labels. */
    val dim: Color,
    val divider: Color,
    val isDark: Boolean,
)

fun colorsFor(palette: Palette): PlainColors = when (palette) {
    Palette.LIGHT -> PlainColors(
        background = Color(0xFFFFFFFF),
        foreground = Color(0xFF000000),
        dim = Color(0xFF767676),
        divider = Color(0xFFE2E2E2),
        isDark = false,
    )

    Palette.DARK -> PlainColors(
        background = Color(0xFF000000),
        foreground = Color(0xFFFFFFFF),
        dim = Color(0xFF8C8C8C),
        divider = Color(0xFF242424),
        isDark = true,
    )

    // Soft gold on black. Pulled back from the old #FFB000, which read as
    // orange on an OLED: less saturation and a shift towards yellow, which is
    // easier on the eye for the same near-zero blue output.
    Palette.AMBER -> PlainColors(
        background = Color(0xFF000000),
        foreground = Color(0xFFF0C674),
        dim = Color(0xFF8C7645),
        divider = Color(0xFF2A2210),
        isDark = true,
    )

    // The daylight counterpart: warm ink on warm paper, still no harsh white.
    Palette.WARM -> PlainColors(
        background = Color(0xFFFBF3DF),
        foreground = Color(0xFF3A2E14),
        dim = Color(0xFF857A5F),
        divider = Color(0xFFE7DCC1),
        isDark = false,
    )
}

@Immutable
data class PlainTypography(
    val clock: TextStyle,
    val date: TextStyle,
    /** App names, favourites, settings rows. */
    val item: TextStyle,
    val label: TextStyle,
    val hint: TextStyle,
)

private fun familyFor(typeface: Typeface): FontFamily = when (typeface) {
    Typeface.SANS -> FontFamily.SansSerif
    Typeface.SERIF -> FontFamily.Serif
    Typeface.MONO -> FontFamily.Monospace
}

fun typographyFor(settings: Settings): PlainTypography {
    val family = familyFor(settings.typeface)
    val scale = settings.textScale
    return PlainTypography(
        clock = TextStyle(
            fontFamily = family,
            fontSize = (settings.clockSize.sp * scale).sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-1).sp,
        ),
        date = TextStyle(fontFamily = family, fontSize = (15 * scale).sp, fontWeight = FontWeight.Normal),
        item = TextStyle(fontFamily = family, fontSize = (21 * scale).sp, fontWeight = FontWeight.Normal),
        label = TextStyle(fontFamily = family, fontSize = (13 * scale).sp, fontWeight = FontWeight.Normal),
        hint = TextStyle(fontFamily = family, fontSize = (14 * scale).sp, fontWeight = FontWeight.Normal),
    )
}

val LocalPlainColors = staticCompositionLocalOf { colorsFor(Palette.DARK) }
val LocalPlainTypography = staticCompositionLocalOf { typographyFor(Settings.DEFAULT) }
val LocalSettings = staticCompositionLocalOf { Settings.DEFAULT }

@Composable
fun PlainTheme(settings: Settings, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPlainColors provides colorsFor(settings.palette),
        LocalPlainTypography provides typographyFor(settings),
        LocalSettings provides settings,
        content = content,
    )
}

/** Applies the "lowercase everything" preference at the point of display. */
@Composable
fun String.cased(): String = if (LocalSettings.current.lowercase) lowercase() else this
