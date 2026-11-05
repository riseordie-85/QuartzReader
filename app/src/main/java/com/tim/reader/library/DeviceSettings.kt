package com.tim.reader.library

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class TwoPageMode(val label: String) {
    Auto("Automatic"),
    Always("Always"),
    Never("Never"),
}

/** Settings for this device only, which are not synced between devices. */
object DeviceSettings {
    private const val KEY_SPLIT_VIEW = "split_view"
    private const val KEY_TWO_PAGE = "two_page"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private var preferences: SharedPreferences? = null

    var splitView by mutableStateOf(true)
        private set

    var twoPage by mutableStateOf(TwoPageMode.Auto)
        private set

    var keepScreenOn by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val saved = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
        preferences = saved
        splitView = saved.getBoolean(KEY_SPLIT_VIEW, true)
        twoPage = TwoPageMode.entries.firstOrNull { it.name == saved.getString(KEY_TWO_PAGE, null) } ?: TwoPageMode.Auto
        keepScreenOn = saved.getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    fun updateTwoPage(mode: TwoPageMode) {
        twoPage = mode
        preferences?.edit { putString(KEY_TWO_PAGE, mode.name) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        keepScreenOn = enabled
        preferences?.edit { putBoolean(KEY_KEEP_SCREEN_ON, enabled) }
    }

    fun updateSplitView(enabled: Boolean) {
        splitView = enabled
        preferences?.edit { putBoolean(KEY_SPLIT_VIEW, enabled) }
    }
}
