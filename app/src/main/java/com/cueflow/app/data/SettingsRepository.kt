package com.cueflow.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value).normalized()
        prefs.edit()
            .putString(KEY_THEME, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putInt(KEY_SPEED, next.defaultSpeed)
            .putInt(KEY_FONT, next.defaultFontSize)
            .putFloat(KEY_LINE_HEIGHT, next.defaultLineHeight)
            .putInt(KEY_COUNTDOWN, next.defaultCountdown)
            .putBoolean(KEY_MIRROR_H, next.mirrorHorizontal)
            .putBoolean(KEY_MIRROR_V, next.mirrorVertical)
            .putBoolean(KEY_KEEP_AWAKE, next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    fun reset() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, defaults.themeMode.name).orEmpty())
            }.getOrDefault(defaults.themeMode),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, defaults.dynamicColor),
            defaultSpeed = prefs.getInt(KEY_SPEED, defaults.defaultSpeed),
            defaultFontSize = prefs.getInt(KEY_FONT, defaults.defaultFontSize),
            defaultLineHeight = prefs.getFloat(KEY_LINE_HEIGHT, defaults.defaultLineHeight),
            defaultCountdown = prefs.getInt(KEY_COUNTDOWN, defaults.defaultCountdown),
            mirrorHorizontal = prefs.getBoolean(KEY_MIRROR_H, defaults.mirrorHorizontal),
            mirrorVertical = prefs.getBoolean(KEY_MIRROR_V, defaults.mirrorVertical),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_AWAKE, defaults.keepScreenOn),
        ).normalized()
    }

    private companion object {
        const val PREFS_NAME = "cueflow_settings"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_SPEED = "default_speed"
        const val KEY_FONT = "default_font"
        const val KEY_LINE_HEIGHT = "default_line_height"
        const val KEY_COUNTDOWN = "default_countdown"
        const val KEY_MIRROR_H = "mirror_h"
        const val KEY_MIRROR_V = "mirror_v"
        const val KEY_KEEP_AWAKE = "keep_awake"
    }
}
