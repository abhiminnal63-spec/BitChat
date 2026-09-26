package com.example.util

import android.content.Context
import android.content.SharedPreferences
import com.example.ui.theme.EasappColorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("easapp_theme_prefs", Context.MODE_PRIVATE)

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("key_dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _selectedTheme = MutableStateFlow(
        EasappColorTheme.fromId(
            prefs.getString("key_color_theme", EasappColorTheme.FLUORESCENT_GREEN.id)
        )
    )
    val selectedTheme: StateFlow<EasappColorTheme> = _selectedTheme.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        prefs.edit().putBoolean("key_dark_mode", enabled).apply()
    }

    fun toggleDarkMode() {
        setDarkMode(!_isDarkMode.value)
    }

    fun setColorTheme(theme: EasappColorTheme) {
        _selectedTheme.value = theme
        prefs.edit().putString("key_color_theme", theme.id).apply()
    }
}
