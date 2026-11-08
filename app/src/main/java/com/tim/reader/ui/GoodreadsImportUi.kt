package com.tim.reader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.GoodreadsPlan
import com.tim.reader.stats.planGoodreadsImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A button that reads a Goodreads export, shows what it found, and imports it on OK. */
@Composable
fun GoodreadsImportButton() {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    var plan by remember { mutableStateOf<GoodreadsPlan?>(null) }
    var working by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        appScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Couldn't open the file")
                    planGoodreadsImport(text, repository.state.value.books, repository.readingHistory())
                }
                withContext(Dispatchers.Main) {
                    if (found.reads.isEmpty() && found.skipped == 0) toast("No read books found in that file") else plan = found
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { toast("Couldn't read that file: ${error.message}") }
            } finally {
                working = false
            }
        }
    }

    OutlinedButton(
        { pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
        Modifier.fillMaxWidth(),
        enabled = !working,
    ) { Text(if (working) "Reading the export…" else "Import from Goodreads") }

    plan?.let { found ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Import from Goodreads") },
            text = {
                Column {
                    val total = found.matched + found.elsewhere
                    Text("$total read ${if (total == 1) "book" else "books"} to bring in.")
                    if (found.matched > 0) Text("${found.matched} match books in your library.")
                    if (found.elsewhere > 0) Text("${found.elsewhere} will be added as read elsewhere. They count in your stats but don't go on shelves.")
                    if (found.ratings > 0) Text("${found.ratings} ratings, ${found.reviews} with reviews.")
                    if (found.undated > 0) Text("${found.undated} have no date read, so they count in your totals but not by month or year.")
                    if (found.skipped > 0) Text("${found.skipped} were imported before and will be skipped.")
                }
            },
            confirmButton = {
                TextButton({
                    plan = null
                    if (found.reads.isEmpty()) return@TextButton
                    appScope.launch {
                        repository.importReads(found.reads)
                        AchievementCenter.check(context)
                        withContext(Dispatchers.Main) { toast("Imported ${found.reads.size} reads from Goodreads") }
                    }
                }) { Text(if (found.reads.isEmpty()) "OK" else "Import") }
            },
            dismissButton = { TextButton({ plan = null }) { Text("Cancel") } },
        )
    }
}
