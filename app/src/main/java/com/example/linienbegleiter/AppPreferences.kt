package com.example.linienbegleiter

import android.content.Context

enum class AppThemeMode {
    AUTO,
    DAY,
    NIGHT
}

object AppPreferences {
    private const val FILE_NAME = "linienbegleiter_preferences"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_AUTO_FOLLOW = "auto_follow"
    private const val KEY_PREFERRED_ZOOM = "preferred_zoom"
    private const val KEY_SHOW_LOCATION = "show_location"
    private const val KEY_EDITOR_SNAP = "editor_snap"
    private const val KEY_DRIVING_ANALYSIS = "driving_analysis_enabled"

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun themeMode(context: Context): AppThemeMode =
        runCatching {
            AppThemeMode.valueOf(preferences(context).getString(KEY_THEME_MODE, null) ?: "AUTO")
        }.getOrDefault(AppThemeMode.AUTO)

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        preferences(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun keepScreenOn(context: Context): Boolean =
        preferences(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    fun autoFollow(context: Context): Boolean =
        preferences(context).getBoolean(KEY_AUTO_FOLLOW, false)

    fun setAutoFollow(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_FOLLOW, enabled).apply()
    }

    fun preferredZoom(context: Context): Float =
        preferences(context).getFloat(KEY_PREFERRED_ZOOM, 15.5f).coerceIn(10f, 19f)

    fun setPreferredZoom(context: Context, zoom: Float) {
        preferences(context).edit().putFloat(KEY_PREFERRED_ZOOM, zoom.coerceIn(10f, 19f)).apply()
    }

    fun showLocation(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SHOW_LOCATION, true)

    fun setShowLocation(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_SHOW_LOCATION, enabled).apply()
    }

    fun editorSnapToRoad(context: Context): Boolean =
        preferences(context).getBoolean(KEY_EDITOR_SNAP, false)

    fun setEditorSnapToRoad(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_EDITOR_SNAP, enabled).apply()
    }

    fun drivingAnalysisEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DRIVING_ANALYSIS, false)

    fun setDrivingAnalysisEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DRIVING_ANALYSIS, enabled).apply()
    }
}
