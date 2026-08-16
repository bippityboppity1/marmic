package com.marmic.plain.data

enum class Palette(val label: String) {
    /** Black on white. */
    LIGHT("black on white"),

    /** White on black. */
    DARK("white on black"),

    /** Amber on black — the low-blue-light one, and cheap on OLED. */
    AMBER("amber on black"),

    /** Warm dark ink on warm paper: the daylight counterpart to amber. */
    WARM("warm on paper"),
}

enum class Typeface(val label: String) {
    SANS("sans"),
    SERIF("serif"),
    MONO("mono"),
}

enum class ClockSize(val label: String, val sp: Int) {
    OFF("off", 0),
    SMALL("small", 34),
    MEDIUM("medium", 54),
    LARGE("large", 76),
}

data class Settings(
    val palette: Palette = Palette.DARK,
    val typeface: Typeface = Typeface.SANS,
    val textScale: Float = 1f,
    val lowercase: Boolean = true,

    val clockSize: ClockSize = ClockSize.MEDIUM,
    val clock24h: Boolean = true,
    val showDate: Boolean = true,
    val showBattery: Boolean = false,

    val showWallpaper: Boolean = false,
    /** 0f = wallpaper untouched, 1f = fully covered by the palette background. */
    val wallpaperDim: Float = 0.45f,

    /** [AppEntry.key] values, in display order. */
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** [AppEntry.key] -> user-chosen label. */
    val renames: Map<String, String> = emptyMap(),

    val searchAutoKeyboard: Boolean = true,
    val launchOnSingleMatch: Boolean = false,

    val doubleTapToLock: Boolean = true,
    val swipeDownForNotifications: Boolean = true,
) {
    companion object {
        val DEFAULT = Settings()

        const val MIN_TEXT_SCALE = 0.7f
        const val MAX_TEXT_SCALE = 1.8f
        const val MAX_FAVORITES = 12
    }
}
