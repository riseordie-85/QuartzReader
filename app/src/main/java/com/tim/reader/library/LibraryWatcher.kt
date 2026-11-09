package com.tim.reader.library

import android.content.Context
import android.widget.Toast
import com.tim.reader.ResultsLog
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Keeps up with the library folder when the app opens or comes back. New files in the type folders,
 * such as books synced from another device, are added where they are. Books dropped in the Import
 * folder are sorted into place, on the one device chosen to do it, so two synced devices never
 * sort the same file at once.
 */
object LibraryWatcher {
    private const val GAP_MS = 2 * 60_000L

    private val lock = Mutex()
    private var last = 0L

    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - last < GAP_MS) return
        if (!LibraryFolder.get(app).isReady()) return
        // A new install's empty library is handled by the restore sheet, which asks first.
        if (LibraryRepository.get(app).state.value.books.isEmpty()) return
        if (!lock.tryLock()) return
        last = now
        appScope.launch(Dispatchers.IO) {
            try {
                if (Importer.running.get() > 0) return@launch
                val importer = Importer(app)
                val messages = ArrayList<String>()

                if (DeviceSettings.autoAdd) {
                    val (found, _) = importer.scanLibrary()
                    if (found.isNotEmpty()) {
                        val result = importer.run(found, options(found, removeOriginals = false)) {}
                        if (result.imported > 0) messages += "Added ${count(result.imported)} from your library folder"
                    }
                }

                if (DeviceSettings.sortImport) {
                    val (found, _) = importer.scanImportFolder()
                    if (found.isNotEmpty()) {
                        val result = importer.run(found, options(found, removeOriginals = true)) {}
                        if (result.imported > 0) messages += "Sorted ${count(result.imported)} from Import"
                        if (result.duplicates > 0) messages += "${count(result.duplicates)} already in your library stayed in Import"
                    }
                }

                if (messages.isNotEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(app, messages.joinToString("\n"), Toast.LENGTH_LONG).show() }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ResultsLog.add("Checking the library folder failed: ${error.message}")
            } finally {
                lock.unlock()
            }
        }
    }

    private fun count(n: Int) = if (n == 1) "1 book" else "$n books"

    private fun options(items: List<ImportItem>, removeOriginals: Boolean) = ImportOptions(
        types = items.associateWith { it.guess },
        shelfId = null,
        addToReadList = false,
        removeOriginals = removeOriginals,
    )
}
