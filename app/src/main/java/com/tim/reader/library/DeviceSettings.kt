package com.tim.reader.library

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Settings for this device only, which are not synced between devices. */
object DeviceSettings {
    private const val KEY_SPLIT_VIEW = "split_view"
    private var preferences: SharedPreferences? = null

    var splitView by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val saved = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
        preferences = saved
        splitView = saved.getBoolean(KEY_SPLIT_VIEW, true)
    }

    fun updateSplitView(enabled: Boolean) {
        splitView = enabled
        preferences?.edit { putBoolean(KEY_SPLIT_VIEW, enabled) }
    }
}
