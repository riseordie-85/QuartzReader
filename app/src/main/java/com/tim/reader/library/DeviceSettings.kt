package com.tim.reader.library

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class TwoPageMode(val label: String) {
    Auto("In landscape"),
    Always("Always"),
    Never("Never"),
}

/** Settings for this device only, which are not synced between devices. */
object DeviceSettings {
    private const val KEY_SPLIT_VIEW = "split_view"
    private const val KEY_TWO_PAGE = "two_page"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_CAMERA_GAP = "camera_gap"
    private const val PREFIX_BOOKS_PER_ROW = "books_per_row:"
    private var preferences: SharedPreferences? = null
    private val rows = mutableStateMapOf<String, Int>()

    var splitView by mutableStateOf(true)
        private set

    var twoPage by mutableStateOf(TwoPageMode.Auto)
        private set

    var keepScreenOn by mutableStateOf(true)
        private set

    /** Extra space in dp, for a camera in the screen that Android doesn't report. */
    var cameraGap by mutableStateOf(0)
        private set

    fun load(context: Context) {
        val saved = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
        preferences = saved
        splitView = saved.getBoolean(KEY_SPLIT_VIEW, true)
        twoPage = TwoPageMode.entries.firstOrNull { it.name == saved.getString(KEY_TWO_PAGE, null) } ?: TwoPageMode.Auto
        keepScreenOn = saved.getBoolean(KEY_KEEP_SCREEN_ON, true)
        cameraGap = saved.getInt(KEY_CAMERA_GAP, 0)
        rows.clear()
        saved.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX_BOOKS_PER_ROW) && value is Int) {
                rows[key.removePrefix(PREFIX_BOOKS_PER_ROW)] = value
            }
        }
    }

    /** The chosen number of books across, or null to fit the screen automatically. */
    fun booksPerRow(key: String): Int? = rows[key]

    fun updateBooksPerRow(key: String, count: Int?) {
        if (count == null) rows.remove(key) else rows[key] = count
        preferences?.edit {
            if (count == null) remove(PREFIX_BOOKS_PER_ROW + key) else putInt(PREFIX_BOOKS_PER_ROW + key, count)
        }
    }

    fun updateCameraGap(dp: Int) {
        cameraGap = dp
        preferences?.edit { putInt(KEY_CAMERA_GAP, dp) }
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
