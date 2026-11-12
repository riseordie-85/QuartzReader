package com.tim.reader.library

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.UUID

enum class TwoPageMode(val label: String) {
    Auto("In landscape"),
    Always("Always"),
    Never("Never"),
}

/** Which edge a camera sits on, for keeping text clear of it. */
enum class CameraEdge(val label: String) {
    Auto("Auto"),
    Top("Top"),
    Sides("Sides"),
    Bottom("Bottom"),
}

/** Settings for this device only, which are not synced between devices. */
object DeviceSettings {
    private const val KEY_SPLIT_VIEW = "split_view"
    private const val KEY_TWO_PAGE = "two_page"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_SHOW_TIME_LEFT = "show_time_left"
    private const val KEY_HOME_GOALS = "home_goals"
    private const val KEY_REMINDER_ON = "reminder_on"
    private const val KEY_SYNC_FOLDER = "sync_folder"
    private const val KEY_AUTO_ADD = "auto_add"
    private const val KEY_SORT_IMPORT = "sort_import"
    private const val KEY_REMINDER_MINUTES = "reminder_minutes"
    private const val KEY_CAMERA_GAP = "camera_gap"
    private const val KEY_GOOGLE_BOOKS = "google_books_key"
    private const val KEY_COMIC_VINE = "comic_vine_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_EREADER_ADDRESS = "ereader_address"
    const val DEFAULT_EREADER_ADDRESS = "http://quartzink.local/"
    private const val PREFIX_BOOKS_PER_ROW = "books_per_row:"
    private const val PREFIX_CAMERA_GAP = "camera_gap:"
    private const val PREFIX_CAMERA_EDGE = "camera_edge:"
    private var preferences: SharedPreferences? = null
    private val rows = mutableStateMapOf<String, Int>()

    /** A random name for this device, so history synced from several devices can tell them apart. */
    var deviceId = "unknown"
        private set

    var splitView by mutableStateOf(true)
        private set

    var twoPage by mutableStateOf(TwoPageMode.Auto)
        private set

    var keepScreenOn by mutableStateOf(true)
        private set

    /** Keeps the time left in a corner while reading, not just with the controls. */
    var showTimeLeft by mutableStateOf(false)
        private set

    /** Shows goal rings on Home. */
    var homeGoals by mutableStateOf(false)
        private set

    fun updateShowTimeLeft(enabled: Boolean) {
        showTimeLeft = enabled
        preferences?.edit { putBoolean(KEY_SHOW_TIME_LEFT, enabled) }
    }

    /** Adds new files found in the type folders, such as books synced from another device. */
    var autoAdd by mutableStateOf(true)
        private set

    /** Sorts books dropped in the Import folder. Only one synced device should do this. */
    var sortImport by mutableStateOf(false)
        private set

    fun updateAutoAdd(enabled: Boolean) {
        autoAdd = enabled
        preferences?.edit { putBoolean(KEY_AUTO_ADD, enabled) }
    }

    fun updateSortImport(enabled: Boolean) {
        sortImport = enabled
        preferences?.edit { putBoolean(KEY_SORT_IMPORT, enabled) }
    }

    /** Keeps the library's data in the library folder, for backup and folder sync. */
    var syncFolder by mutableStateOf(true)
        private set

    fun updateSyncFolder(enabled: Boolean) {
        syncFolder = enabled
        preferences?.edit { putBoolean(KEY_SYNC_FOLDER, enabled) }
    }

    /** The evening reminder, and its time as minutes after midnight. */
    var reminderOn by mutableStateOf(false)
        private set
    var reminderMinutes by mutableStateOf(20 * 60)
        private set

    fun updateReminder(enabled: Boolean, minutes: Int = reminderMinutes) {
        reminderOn = enabled
        reminderMinutes = minutes.coerceIn(0, 24 * 60 - 1)
        preferences?.edit {
            putBoolean(KEY_REMINDER_ON, enabled)
            putInt(KEY_REMINDER_MINUTES, reminderMinutes)
        }
    }

    fun updateHomeGoals(enabled: Boolean) {
        homeGoals = enabled
        preferences?.edit { putBoolean(KEY_HOME_GOALS, enabled) }
    }

    /** Extra space in dp, for a camera in the screen that Android doesn't report. */
    var cameraGap by mutableStateOf(0)
        private set

    /** Optional keys for online lookups. They stay on this device. */
    var googleBooksKey by mutableStateOf("")
        private set

    var comicVineKey by mutableStateOf("")
        private set

    fun updateLookupKeys(googleBooks: String, comicVine: String) {
        googleBooksKey = googleBooks.trim()
        comicVineKey = comicVine.trim()
        preferences?.edit {
            putString(KEY_GOOGLE_BOOKS, googleBooksKey)
            putString(KEY_COMIC_VINE, comicVineKey)
        }
    }

    /** The address an e-reader's File Transfer page showed last time stats were imported from it. */
    var ereaderAddress by mutableStateOf("")
        private set

    fun updateEreaderAddress(address: String) {
        ereaderAddress = address.trim()
        preferences?.edit { putString(KEY_EREADER_ADDRESS, ereaderAddress) }
    }

    /** Loads settings unless the app already has, for work that runs without the app open. */
    fun ensureLoaded(context: Context) {
        if (preferences == null) load(context)
    }

    fun load(context: Context) {
        val saved = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
        preferences = saved
        deviceId = saved.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { id -> saved.edit { putString(KEY_DEVICE_ID, id) } }
        splitView = saved.getBoolean(KEY_SPLIT_VIEW, true)
        twoPage = TwoPageMode.entries.firstOrNull { it.name == saved.getString(KEY_TWO_PAGE, null) } ?: TwoPageMode.Auto
        keepScreenOn = saved.getBoolean(KEY_KEEP_SCREEN_ON, true)
        showTimeLeft = saved.getBoolean(KEY_SHOW_TIME_LEFT, false)
        homeGoals = saved.getBoolean(KEY_HOME_GOALS, false)
        reminderOn = saved.getBoolean(KEY_REMINDER_ON, false)
        syncFolder = saved.getBoolean(KEY_SYNC_FOLDER, true)
        autoAdd = saved.getBoolean(KEY_AUTO_ADD, true)
        sortImport = saved.getBoolean(KEY_SORT_IMPORT, false)
        reminderMinutes = saved.getInt(KEY_REMINDER_MINUTES, 20 * 60).coerceIn(0, 24 * 60 - 1)
        cameraGap = saved.getInt(KEY_CAMERA_GAP, 0)
        googleBooksKey = saved.getString(KEY_GOOGLE_BOOKS, null).orEmpty()
        comicVineKey = saved.getString(KEY_COMIC_VINE, null).orEmpty()
        // QuartzInk answers to quartzink.local (it used to be crosspoint.local), so an
        // empty or old address points at the new name.
        ereaderAddress = saved.getString(KEY_EREADER_ADDRESS, null).orEmpty()
            .replace("crosspoint.local", "quartzink.local", ignoreCase = true)
            .ifBlank { DEFAULT_EREADER_ADDRESS }
        rows.clear()
        saved.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX_BOOKS_PER_ROW) && value is Int) {
                rows[key.removePrefix(PREFIX_BOOKS_PER_ROW)] = value
            }
            if (key.startsWith(PREFIX_CAMERA_GAP) && value is Int) cameraGaps[key.removePrefix(PREFIX_CAMERA_GAP)] = value
            if (key.startsWith(PREFIX_CAMERA_EDGE) && value is String) {
                CameraEdge.entries.firstOrNull { it.name == value }?.let { cameraEdges[key.removePrefix(PREFIX_CAMERA_EDGE)] = it }
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

    // A folding phone has two screens, and a camera moves edges when the screen turns, so the camera
    // gap is kept for each screen size and orientation. Keys look like "large:landscape".
    private val cameraGaps = mutableStateMapOf<String, Int>()
    private val cameraEdges = mutableStateMapOf<String, CameraEdge>()

    /** The camera gap for this screen and orientation, falling back to the one set before there were several. */
    fun cameraGap(key: String): Int = cameraGaps[key] ?: cameraGap

    fun cameraEdge(key: String): CameraEdge = cameraEdges[key] ?: CameraEdge.Auto

    fun updateCameraGap(key: String, dp: Int) {
        cameraGaps[key] = dp
        preferences?.edit { putInt(PREFIX_CAMERA_GAP + key, dp) }
    }

    fun updateCameraEdge(key: String, edge: CameraEdge) {
        cameraEdges[key] = edge
        preferences?.edit { putString(PREFIX_CAMERA_EDGE + key, edge.name) }
    }

    /** Names the screen being used: the big or small one, held upright or sideways. */
    fun screenKey(smallestWidthDp: Int, landscape: Boolean) =
        "${if (smallestWidthDp >= 600) "large" else "small"}:${if (landscape) "landscape" else "portrait"}"

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
