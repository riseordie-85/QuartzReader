package com.tim.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Shelf
import kotlinx.coroutines.launch

@Composable
fun ShelvesListScreen(
    state: LibraryState,
    onAllBooks: () -> Unit,
    onShelf: (String) -> Unit,
    onEditStyle: (String) -> Unit,
    onNewShelf: () -> Unit,
    onChooseBooks: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Shelf?>(null) }
    var deleting by remember { mutableStateOf<Shelf?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shelves", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onNewShelf) { Text("New shelf") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "All Books (${state.books.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAllBooks)
                        .padding(vertical = 14.dp),
                )
                HorizontalDivider()
            }
            items(state.shelves, key = { it.id }) { shelf ->
                ShelfRowEditor(
                    shelf = shelf,
                    count = state.shelfBooks[shelf.id]?.size ?: 0,
                    onOpen = { onShelf(shelf.id) },
                    onMove = { delta -> scope.launch { repository.moveShelf(shelf.id, delta) } },
                    onRename = { renaming = shelf },
                    onDelete = { deleting = shelf },
                    onStyle = { onEditStyle(shelf.id) },
                    onChooseBooks = { onChooseBooks(shelf.id) },
                )
                HorizontalDivider()
            }
        }
    }

    renaming?.let { shelf ->
        NameDialog("Rename shelf", shelf.name, { renaming = null }) { name ->
            renaming = null
            scope.launch { repository.renameShelf(shelf.id, name) }
        }
    }
    deleting?.let { shelf ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${shelf.name}?") },
            text = { Text("Its books stay in your library.") },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch { repository.deleteShelf(shelf.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShelfRowEditor(
    shelf: Shelf,
    count: Int,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onStyle: () -> Unit,
    onChooseBooks: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${shelf.name} ($count)",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 10.dp),
        )
        TextButton({ onMove(-1) }) { Text("Up") }
        TextButton({ onMove(1) }) { Text("Down") }
        Box {
            TextButton({ menu = true }) { Text("Edit") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(text = { Text("Add or remove books") }, onClick = {
                    menu = false
                    onChooseBooks()
                })
                DropdownMenuItem(text = { Text("Edit style") }, onClick = {
                    menu = false
                    onStyle()
                })
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, start: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)) },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}
