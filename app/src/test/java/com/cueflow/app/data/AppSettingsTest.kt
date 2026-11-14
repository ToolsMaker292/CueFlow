package com.cueflow.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `defaults are sane`() {
        val settings = AppSettings()
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertTrue(settings.defaultSpeed in AppSettings.MIN_SPEED..AppSettings.MAX_SPEED)
        assertTrue(settings.defaultFontSize in AppSettings.MIN_FONT_SIZE..AppSettings.MAX_FONT_SIZE)
    }

    @Test
    fun `normalization clamps sliders and countdown`() {
        val settings = AppSettings(
            defaultSpeed = 999,
            defaultFontSize = 1,
            defaultLineHeight = 9f,
            defaultCountdown = 4,
        ).normalized()
        assertEquals(AppSettings.MAX_SPEED, settings.defaultSpeed)
        assertEquals(AppSettings.MIN_FONT_SIZE, settings.defaultFontSize)
        assertEquals(AppSettings.MAX_LINE_HEIGHT, settings.defaultLineHeight)
        assertEquals(3, settings.defaultCountdown)
    }
}
