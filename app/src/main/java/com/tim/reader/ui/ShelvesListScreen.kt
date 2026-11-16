package com.tim.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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

    // The order while dragging. It's saved when the drag ends, then follows the library again.
    val order = remember { mutableStateListOf(*state.shelves.map { it.id }.toTypedArray()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.shelves, dragging) {
        val ids = state.shelves.map { it.id }
        if (dragging == null && order.toList() != ids) {
            order.clear()
            order.addAll(ids)
        }
    }
    fun finishDrag() {
        offset = 0f
        val ids = order.toList()
        // The dragged order stays until it's saved, so the list doesn't jump back for a moment.
        scope.launch {
            repository.setShelfOrder(ids)
            dragging = null
        }
    }

    SettingsTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTitleBar("Shelves")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsButton("New shelf", Modifier.weight(1f), primary = true, onClick = onNewShelf)
                    SettingsButton("Library (${state.books.size})", Modifier.weight(1f), onClick = onAllBooks)
                }
                SettingsCard("Your shelves", "Drag a shelf by its handle to change the order of Home's buttons and the swipe between shelves.") {
                    if (order.isEmpty()) SettingsHint("No shelves yet.")
                    order.forEachIndexed { index, id ->
                        val shelf = state.shelf(id) ?: return@forEachIndexed
                        key(id) {
                            val isDragging = dragging == id
                            ShelfRowEditor(
                                shelf = shelf,
                                count = state.shelfBooks[shelf.id]?.size ?: 0,
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) offset else 0f
                                        shadowElevation = if (isDragging) 8f else 0f
                                    }
                                    .onSizeChanged { if (index == 0) rowHeight = it.height }
                                    .semantics {
                                        customActions = listOfNotNull(
                                            CustomAccessibilityAction("Move up") {
                                                scope.launch { repository.moveShelf(id, -1) }
                                                true
                                            }.takeIf { index > 0 },
                                            CustomAccessibilityAction("Move down") {
                                                scope.launch { repository.moveShelf(id, 1) }
                                                true
                                            }.takeIf { index < order.lastIndex },
                                        )
                                    },
                                handle = Modifier.pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = id
                                            offset = 0f
                                        },
                                        onDragEnd = { finishDrag() },
                                        onDragCancel = { finishDrag() },
                                    ) { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        // Every row is one height plus the gap, so passing half of one swaps places.
                                        val step = rowHeight + 10.dp.toPx()
                                        val at = order.indexOf(id)
                                        if (step > 0f && offset > step / 2 && at < order.lastIndex) {
                                            order.add(at + 1, order.removeAt(at))
                                            offset -= step
                                        } else if (step > 0f && offset < -step / 2 && at > 0) {
                                            order.add(at - 1, order.removeAt(at))
                                            offset += step
                                        }
                                    }
                                },
                                onOpen = { onShelf(shelf.id) },
                                onRename = { renaming = shelf },
                                onDelete = { deleting = shelf },
                                onStyle = { onEditStyle(shelf.id) },
                                onChooseBooks = { onChooseBooks(shelf.id) },
                            )
                        }
                    }
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
}

@Composable
private fun ShelfRowEditor(
    shelf: Shelf,
    count: Int,
    modifier: Modifier,
    handle: Modifier,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onStyle: () -> Unit,
    onChooseBooks: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(2.dp, SettingsColors.Ink, RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Handle, SettingsColors.Ink, Modifier.size(22.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .clickable(onClickLabel = "Open ${shelf.name}", onClick = onOpen)
                .padding(vertical = 10.dp),
        ) {
            Text(shelf.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (count == 1) "1 book" else "$count books", color = SettingsColors.Muted, fontSize = 13.sp)
        }
        Box {
            SettingsButton("Edit", Modifier.padding(end = 4.dp)) { menu = true }
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
