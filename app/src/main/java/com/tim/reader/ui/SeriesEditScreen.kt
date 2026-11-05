package com.tim.reader.ui

import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.tim.reader.library.LibraryFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one value every volume shares, or null when they differ. */
private fun <T> common(volumes: List<Book>, read: (Book) -> T): T? =
    volumes.map(read).distinct().singleOrNull()

/** Changes every volume of a series in one save. */
@Composable
fun SeriesEditScreen(series: Series, volumes: List<Book>, state: LibraryState, onDone: () -> Unit) {
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

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Edit series", style = MaterialTheme.typography.headlineMedium)
        Text("Changes apply to all ${volumes.size} volumes.")

        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Series name") }, singleLine = true)
        Text("Using the name of another series merges the two.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            author,
            { author = it },
            Modifier.fillMaxWidth(),
            label = { Text("Author") },
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
                    if (newType != null) {
                        val folder = LibraryFolder.get(context)
                        val moved = volumes.filter { it.type != newType }.mapNotNull { book ->
                            folder.moveBetweenTypes(Uri.parse(book.documentUri), book.type, newType)
                                ?.let { book.id to it.toString() }
                        }.toMap()
                        repository.setDocumentUris(moved)
                    }
                    repository.updateSeries(series.id, edit)
                    withContext(Dispatchers.Main) { onDone() }
                }
            },
            enabled = !saving && title.isNotBlank(),
        ) { Text(if (saving) "Saving" else "Save") }
    }
}
