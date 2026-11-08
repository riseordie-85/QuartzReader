package com.tim.reader.library

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.tim.reader.ResultsLog
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.stats.AchievementCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Where backup and sync stand, for Settings. */
data class SyncStatus(
    val lastSaved: Long? = null,
    val lastChecked: Long? = null,
    /** Other devices' snapshots in the folder, as name and when each was written. */
    val devices: List<Pair<String, Long>> = emptyList(),
    val problem: String? = null,
)

/**
 * Keeps the library's data in the library folder, so a reinstall can bring it back and any folder
 * sync (Syncthing, Nextcloud, a cloud drive app) carries it between devices.
 *
 * Each device writes only its own snapshot, "quartz-<device>.json" in "Library Data", so two devices
 * never write the same file and a sync tool never has to choose between them. Each device reads
 * the others' snapshots and merges them in. Custom covers go in "Library Data/Covers".
 */
object LibrarySync {
    private const val COVERS = "Covers"
    private const val PREFIX = "quartz-"
    // Only snapshots this app wrote; sync tools' conflict copies and other files are left alone.
    private val SNAPSHOT = Regex("""^quartz-([0-9a-f]{8})\.json(\.tmp)?$""")
    private const val SAVE_DELAY_MS = 15_000L
    private const val CHECK_GAP_MS = 30_000L

    private val lock = Mutex()
    private val state = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = state
    private var watching = false
    private var lastCheck = 0L

    private fun fileName() = "$PREFIX${DeviceSettings.deviceId.take(8)}.json"

    private fun deviceName() = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ")

    private fun enabled(context: Context) = DeviceSettings.syncFolder && LibraryFolder.get(context).isReady()

    /** Saves a little after each change, so a burst of page turns makes one write. */
    @OptIn(FlowPreview::class)
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        appScope.launch {
            LibraryRepository.get(app).edits.drop(1).debounce(SAVE_DELAY_MS).collect { save(app) }
        }
    }

    /** Writes this device's snapshot and any new custom covers. */
    fun save(context: Context) {
        val app = context.applicationContext
        if (!enabled(app)) return
        appScope.launch(Dispatchers.IO) {
            lock.withLock {
                runStep("Couldn't save library data") {
                    val folder = LibraryFolder.get(app)
                    val repository = LibraryRepository.get(app)
                    // Covers go first, so a snapshot never names a cover that isn't in the folder yet.
                    val covers = folder.dataFolder(COVERS)
                    repository.state.value.books.mapNotNull { it.customCoverPath }.forEach { path ->
                        File(path).takeIf { it.exists() }?.let { folder.saveFileOnce(covers, it) }
                    }
                    val snapshot = repository.exportSnapshot(DeviceSettings.deviceId, deviceName())
                    folder.writeFile(folder.dataFolder(), fileName(), "application/json", snapshot.toString().toByteArray())
                    state.value = state.value.copy(lastSaved = System.currentTimeMillis(), problem = null)
                }
            }
        }
    }

    /**
     * Reads other devices' snapshots that changed since last time and merges them in. [force] reads
     * every snapshot again, such as right after a reinstall.
     */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!enabled(app)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_GAP_MS) return
        lastCheck = now
        appScope.launch(Dispatchers.IO) {
            lock.withLock {
                runStep("Couldn't read library data") {
                    val folder = LibraryFolder.get(app)
                    val repository = LibraryRepository.get(app)
                    val seen = app.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    val own = DeviceSettings.deviceId.take(8)
                    // One file per other device: its snapshot, or the new copy if a save was cut off before the swap.
                    val others = folder.files(folder.dataFolder())
                        .mapNotNull { file -> SNAPSHOT.matchEntire(file.name)?.let { it.groupValues[1] to file } }
                        .filter { (device, _) -> device != own }
                        .groupBy({ it.first }, { it.second })
                        .mapNotNull { (_, files) -> files.firstOrNull { !it.name.endsWith(".tmp") } ?: files.firstOrNull() }
                    val devices = ArrayList<Pair<String, Long>>()
                    var merged = false
                    for (file in others) {
                        val key = "seen:${file.name}"
                        val remote = runCatching { JSONObject(folder.readFile(file.documentId).decodeToString()) }.getOrNull() ?: continue
                        devices += remote.optString("deviceName", file.name) to remote.optLong("written")
                        if (!force && seen.getLong(key, 0L) >= file.lastModified) continue
                        val result = repository.mergeSnapshot(remote)
                        val allCovers = result.covers.isEmpty() || fetchCovers(app, folder, result.covers)
                        // A cover still on its way means reading this snapshot again next time.
                        if (allCovers) seen.edit { putLong(key, file.lastModified) }
                        merged = merged || result.changed > 0
                    }
                    state.value = state.value.copy(lastChecked = System.currentTimeMillis(), devices = devices, problem = null)
                    if (merged) AchievementCenter.check(app)
                }
            }
        }
    }

    /** Copies covers from the folder. Returns false if any hasn't arrived yet. */
    private suspend fun fetchCovers(context: Context, folder: LibraryFolder, covers: List<Pair<String, String>>): Boolean {
        val available = folder.files(folder.dataFolder(COVERS)).associateBy { it.name }
        val repository = LibraryRepository.get(context)
        val target = File(context.filesDir, "custom-covers").apply { mkdirs() }
        var all = true
        for ((bookId, name) in covers) {
            val file = available[name]
            if (file == null) {
                all = false
                continue
            }
            val copy = File(target, name)
            if (!copy.exists()) {
                val partial = File(target, "$name.part")
                partial.writeBytes(folder.readFile(file.documentId))
                partial.renameTo(copy)
            }
            val old = repository.state.value.book(bookId)?.customCoverPath
            repository.setSyncedCover(bookId, copy.path)
            if (old != null && old != copy.path) Covers.deleteCustom(old)
        }
        return all
    }

    private suspend fun runStep(problem: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ResultsLog.add("$problem: ${error.message}")
            state.value = state.value.copy(problem = "$problem: ${error.message}")
        }
    }
}
