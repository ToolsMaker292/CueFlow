package com.cueflow.app.data

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultSpeed: Int = 32,
    val defaultFontSize: Int = 32,
    val defaultLineHeight: Float = 1.5f,
    val defaultCountdown: Int = 3,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val keepScreenOn: Boolean = true,
) {
    fun normalized(): AppSettings = copy(
        defaultSpeed = defaultSpeed.coerceIn(MIN_SPEED, MAX_SPEED),
        defaultFontSize = defaultFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
        defaultLineHeight = defaultLineHeight.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
        defaultCountdown = defaultCountdown.takeIf { it in COUNTDOWN_OPTIONS } ?: 3,
    )

    companion object {
        const val MIN_SPEED = 10
        const val MAX_SPEED = 120
        const val MIN_FONT_SIZE = 24
        const val MAX_FONT_SIZE = 64
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.0f
        val COUNTDOWN_OPTIONS = listOf(0, 3, 5)
    }
}
