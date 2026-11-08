package com.tim.reader.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.ReadDirection
import com.tim.reader.library.deleteBooks
import com.tim.reader.library.LibraryFolder
import com.tim.reader.library.LibraryOrganizer
import com.tim.reader.library.rememberCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BookDetailsScreen(
    book: Book,
    state: LibraryState,
    onOpen: () -> Unit,
    onFindInfo: () -> Unit,
    onGone: () -> Unit,
) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
    // Keyed on the values too, so a lookup that changes them shows here instead of being saved over.
    var title by remember(book.id, book.title) { mutableStateOf(book.title) }
    var author by remember(book.id, book.author) { mutableStateOf(book.author) }
    var series by remember(book.id) { mutableStateOf(state.seriesFor(book.seriesId)?.title.orEmpty()) }
    var number by remember(book.id) { mutableStateOf(book.seriesNumber?.let(::formatNumber).orEmpty()) }
    var type by remember(book.id) { mutableStateOf(book.type) }
    var direction by remember(book.id) { mutableStateOf(book.direction) }
    var inReadList by remember(book.id) { mutableStateOf(book.inReadList) }
    val shelfIds = remember(book.id) {
        mutableStateListOf(*state.shelfBooks.filterValues { book.id in it }.keys.toTypedArray())
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var changingCover by remember { mutableStateOf(false) }
    var seriesFocused by remember { mutableStateOf(false) }
    val cover by rememberCover(book.displayCover)
    if (changingCover) CoverOptionsDialog(book) { changingCover = false }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cover?.let {
                Image(bitmap = it, contentDescription = book.title, modifier = Modifier.width(120.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Text("${(book.progress * 100).toInt()}% read")
                Button(onOpen) { Text(if (book.progress > 0f) "Continue reading" else "Read") }
                // Leaving for the lookup would lose typing, so it waits until the edits are saved.
                val edited = title != book.title || author != book.author ||
                    series != state.seriesFor(book.seriesId)?.title.orEmpty() ||
                    number != book.seriesNumber?.let(::formatNumber).orEmpty() ||
                    type != book.type || direction != book.direction || inReadList != book.inReadList ||
                    shelfIds.toSet() != state.shelfBooks.filterValues { book.id in it }.keys
                OutlinedButton(onFindInfo, enabled = !edited) { Text("Find info online") }
                if (edited) Text("Save your changes first", style = MaterialTheme.typography.bodySmall)
                TextButton({ changingCover = true }) { Text("Change cover") }
            }
        }

        BookInfoCard(book)

        // Saving a rating writes history, so the counter reloads it and the editor shows what was saved.
        var ratingVersion by remember(book.id) { mutableStateOf(0) }
        val history = rememberHistory(book.id to ratingVersion)
        if (history != null) {
            RatingEditor(history.ratings[book.hash]) { rating ->
                appScope.launch {
                    saveBookRating(context, book, rating)
                    ratingVersion++
                }
            }
            ReadsSection(book, history) { ratingVersion++ }
        }

        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("Author") })
        OutlinedTextField(
            series,
            { series = it },
            Modifier
                .fillMaxWidth()
                .onFocusChanged { seriesFocused = it.isFocused },
            label = { Text("Series") },
            singleLine = true,
        )
        if (seriesFocused) {
            state.series
                .distinctBy { it.title.lowercase() }
                .filter { it.title.contains(series.trim(), ignoreCase = true) && !it.title.equals(series.trim(), ignoreCase = true) }
                .sortedBy { it.title.lowercase() }
                .take(6)
                .forEach { option ->
                    Text(
                        option.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { series = option.title }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
        }
        OutlinedTextField(
            number,
            { number = it },
            Modifier.fillMaxWidth(),
            label = { Text("Volume number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Text("Type", style = MaterialTheme.typography.titleMedium)
        BookType.entries.forEach { option ->
            ChoiceRow(option.label, type == option) {
                // The book follows its type to the matching shelf.
                state.shelves.filter { it.defaultType == type }.forEach { shelfIds.remove(it.id) }
                state.shelves.filter { it.defaultType == option && it.id !in shelfIds }.forEach { shelfIds.add(it.id) }
                type = option
                direction = option.direction
            }
        }

        Text("Read direction", style = MaterialTheme.typography.titleMedium)
        ReadDirection.entries.forEach { option ->
            ChoiceRow(option.label, direction == option) { direction = option }
        }

        ToggleRow("Read List", inReadList) { inReadList = it }

        Text("Shelves", style = MaterialTheme.typography.titleMedium)
        state.shelves.forEach { shelf ->
            ToggleRow(shelf.name, shelf.id in shelfIds) { checked ->
                if (checked) shelfIds.add(shelf.id) else shelfIds.remove(shelf.id)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                // This carries on if the screen closes, so a moved file is never left unrecorded.
                appScope.launch {
                    repository.updateBookDetails(
                        book.id,
                        title.trim().ifBlank { book.title },
                        author.trim(),
                        series,
                        number.toDoubleOrNull(),
                        type,
                        direction,
                    )
                    repository.setReadList(book.id, inReadList)
                    repository.setBookShelves(book.id, shelfIds.toSet())
                    LibraryOrganizer(context).place(listOf(book.id))
                    withContext(Dispatchers.Main) { onGone() }
                }
            }) { Text("Save") }
            OutlinedButton({ confirmDelete = true }) { Text("Delete") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this book?") },
            text = { Text("This removes it from your library and deletes its copy in your library folder.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch {
                        deleteBooks(context, listOf(book))
                        onGone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** What's known about the book beyond its title, with where it came from. */
@Composable
private fun BookInfoCard(book: Book) {
    val info = book.info
    if (info.isEmpty) return
    var expanded by remember(book.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val facts = listOf(info.publisher, info.year).filter { it.isNotBlank() }
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        if (info.genres.isNotEmpty()) {
            Text(info.genres.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
        if (info.description.isNotBlank()) {
            Text(
                info.description,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        if (info.source.isNotBlank()) {
            Text("From ${info.source}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatNumber(value: Double) =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
