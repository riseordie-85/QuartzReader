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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.ReadDirection
import com.tim.reader.library.Covers
import com.tim.reader.library.LibraryFolder
import com.tim.reader.library.rememberCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BookDetailsScreen(book: Book, state: LibraryState, onOpen: () -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var series by remember(book.id) { mutableStateOf(state.seriesFor(book.seriesId)?.title.orEmpty()) }
    var number by remember(book.id) { mutableStateOf(book.seriesNumber?.let(::formatNumber).orEmpty()) }
    var type by remember(book.id) { mutableStateOf(book.type) }
    var direction by remember(book.id) { mutableStateOf(book.direction) }
    var inReadList by remember(book.id) { mutableStateOf(book.inReadList) }
    val shelfIds = remember(book.id) {
        mutableStateListOf(*state.shelfBooks.filterValues { book.id in it }.keys.toTypedArray())
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val cover by rememberCover(book.displayCover)

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
            }
        }

        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("Author") })
        OutlinedTextField(series, { series = it }, Modifier.fillMaxWidth(), label = { Text("Series") })
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
                scope.launch {
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
                    onGone()
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
                        withContext(Dispatchers.IO) {
                            runCatching { LibraryFolder.get(context).delete(Uri.parse(book.documentUri)) }
                            Covers.delete(context, book.id)
                        }
                        repository.deleteBook(book.id)
                        onGone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun formatNumber(value: Double) =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
