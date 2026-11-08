package com.tim.reader.ui

import com.tim.reader.stats.seriesKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.Series
import com.tim.reader.data.SeriesEdit
import com.tim.reader.library.LibraryOrganizer
import com.tim.reader.library.deleteBooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one value every volume shares, or null when they differ. */
private fun <T> common(volumes: List<Book>, read: (Book) -> T): T? =
    volumes.map(read).distinct().singleOrNull()

/** Changes every volume of a series in one save. */
@Composable
fun SeriesEditScreen(
    series: Series,
    volumes: List<Book>,
    state: LibraryState,
    onFindInfo: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val startType = remember(series.id) { common(volumes) { it.type } }
    val startDirection = remember(series.id) { common(volumes) { it.direction } }
    val startAuthor = remember(series.id) { common(volumes) { it.author }.orEmpty() }
    val startShelves = remember(series.id) {
        val ids = volumes.map { it.id }
        state.shelfBooks.filterValues { members -> ids.all { it in members } }.keys.toSet()
    }
    var title by remember(series.id) { mutableStateOf(series.title) }
    var author by remember(series.id) { mutableStateOf(startAuthor) }
    var type by remember(series.id) { mutableStateOf(startType) }
    var direction by remember(series.id) { mutableStateOf(startDirection) }
    val shelfIds = remember(series.id) { mutableStateListOf(*startShelves.toTypedArray()) }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Edit series", style = MaterialTheme.typography.headlineMedium)
        // Leaving for the lookup would lose typing, so it waits until the edits are saved.
        val edited = title != series.title || author != startAuthor || type != startType ||
            direction != startDirection || shelfIds.toSet() != startShelves
        OutlinedButton(onFindInfo, Modifier.fillMaxWidth(), enabled = !edited) {
            Text(if (edited) "Save your changes to find info online" else "Find series info online")
        }
        Text("Changes apply to all ${volumes.size} volumes.")

        var ratingVersion by remember(series.id) { mutableStateOf(0) }
        val history = rememberHistory(series.id to ratingVersion)
        if (history != null) {
            RatingEditor(history.seriesRatings[seriesKey(series.title)]) { rating ->
                appScope.launch {
                    saveSeriesRating(context, series.title, rating)
                    ratingVersion++
                }
            }
        }

        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Series name") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Text("Using the name of another series of the same type merges the two.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            author,
            { author = it },
            Modifier.fillMaxWidth(),
            label = { Text("Author") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            placeholder = { if (startAuthor.isEmpty()) Text("Leave each volume's author as it is") },
            singleLine = true,
        )

        Text("Type", style = MaterialTheme.typography.titleMedium)
        BookType.entries.forEach { option ->
            ChoiceRow(option.label, type == option) {
                // The series follows its type to the matching shelf.
                state.shelves.filter { it.defaultType != null && it.defaultType == type }.forEach { shelfIds.remove(it.id) }
                state.shelves.filter { it.defaultType == option && it.id !in shelfIds }.forEach { shelfIds.add(it.id) }
                type = option
                direction = option.direction
            }
        }

        Text("Read direction", style = MaterialTheme.typography.titleMedium)
        ReadDirection.entries.forEach { option ->
            ChoiceRow(option.label, direction == option) { direction = option }
        }

        Text("Shelves", style = MaterialTheme.typography.titleMedium)
        state.shelves.forEach { shelf ->
            ToggleRow(shelf.name, shelf.id in shelfIds) { checked ->
                if (checked) shelfIds.add(shelf.id) else shelfIds.remove(shelf.id)
            }
        }

        Button(
            onClick = {
                saving = true
                val selected = shelfIds.toSet()
                val newType = type?.takeIf { it != startType }
                val edit = SeriesEdit(
                    title = title,
                    author = author.trim().takeIf { it != startAuthor },
                    type = newType,
                    direction = direction?.takeIf { it != startDirection },
                    addShelves = selected - startShelves,
                    removeShelves = startShelves - selected,
                )
                // This carries on if the screen closes, so moved files are never left unrecorded.
                appScope.launch {
                    repository.updateSeries(series.id, edit)
                    LibraryOrganizer(context).place(volumes.map { it.id })
                    withContext(Dispatchers.Main) { onDone() }
                }
            },
            enabled = !saving && title.isNotBlank(),
        ) { Text(if (saving) "Saving" else "Save") }
        OutlinedButton({ removing = true }, Modifier.fillMaxWidth(), enabled = !saving) { Text("Remove series") }
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${series.title}?") },
            text = {
                Text(
                    "Ungroup keeps all ${volumes.size} books and only takes them out of the series. " +
                        "Delete removes the books from your library and deletes their copies in your library folder.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({
                        removing = false
                        saving = true
                        appScope.launch {
                            val worked = runCatching {
                                repository.ungroupSeries(series.id)
                                // The books are already ungrouped, so a failed folder move doesn't undo that.
                                runCatching { LibraryOrganizer(context).place(volumes.map { it.id }) }
                            }.isSuccess
                            withContext(Dispatchers.Main) { if (worked) onDone() else saving = false }
                        }
                    }) { Text("Ungroup, keep the books") }
                    TextButton({
                        removing = false
                        saving = true
                        appScope.launch {
                            val worked = runCatching { deleteBooks(context, volumes) }.isSuccess
                            withContext(Dispatchers.Main) { if (worked) onDone() else saving = false }
                        }
                    }) { Text("Delete the series and its ${volumes.size} books", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton({ removing = false }) { Text("Cancel") } },
        )
    }
}
