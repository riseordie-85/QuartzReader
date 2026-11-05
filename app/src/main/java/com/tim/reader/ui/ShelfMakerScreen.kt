package com.tim.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Series
import com.tim.reader.data.Shelf
import com.tim.reader.data.volumeOrder
import com.tim.reader.library.rememberCover
import com.tim.reader.theme.Presets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the book list: a series that opens to its volumes, or a book on its own. */
private sealed interface PickEntry {
    val title: String
    val books: List<Book>

    class OneSeries(val series: Series, override val books: List<Book>) : PickEntry {
        override val title get() = series.title
    }

    class OneBook(val book: Book) : PickEntry {
        override val title get() = book.title
        override val books get() = listOf(book)
    }
}

private fun pickEntries(state: LibraryState, query: String, type: BookType?): List<PickEntry> {
    val search = query.trim().lowercase()
    val matches = state.books.filter { book ->
        val text = listOfNotNull(book.title, book.author, state.seriesFor(book.seriesId)?.title)
        (type == null || book.type == type) && (search.isEmpty() || text.any { search in it.lowercase() })
    }
    val (inSeries, loose) = matches.partition { it.seriesId != null }
    val series: List<PickEntry> = inSeries.groupBy { it.seriesId }.mapNotNull { (id, volumes) ->
        state.seriesFor(id)?.let { PickEntry.OneSeries(it, volumes.sortedWith(volumeOrder)) }
    }
    return (series + loose.map { PickEntry.OneBook(it) }).sortedBy { it.title.lowercase() }
}

@Composable
private fun SmallCover(path: String?) {
    val cover by rememberCover(path)
    Box(
        Modifier
            .size(38.dp, 56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(EditorColors.Well),
    ) {
        cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun PickBookRow(book: Book, chosen: Boolean, onToggle: () -> Unit, indented: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = if (indented) 28.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(chosen, { onToggle() })
        SmallCover(book.displayCover)
        Column(Modifier.weight(1f)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) {
                Text(book.author, color = EditorColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PickSeriesRow(entry: PickEntry.OneSeries, chosen: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val ids = entry.books.map { it.id }.toSet()
    val picked = ids.count { it in chosen }
    val mark = when (picked) {
        0 -> ToggleableState.Off
        ids.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val toggleAll = { onChange(if (picked == ids.size) chosen - ids else chosen + ids) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = toggleAll)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TriStateCheckbox(mark, toggleAll)
        SmallCover(entry.books.first().displayCover)
        Column(Modifier.weight(1f)) {
            Text(entry.series.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val volumes = "${ids.size} volumes"
            Text(if (picked in 1 until ids.size) "$picked of $volumes" else volumes, color = EditorColors.Muted, fontSize = 13.sp)
        }
        // A larger target than the arrow, so opening a series doesn't select it by accident.
        Box(
            Modifier
                .size(44.dp)
                .clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) { ListArrow(EditorColors.Muted) }
    }
    if (open) {
        entry.books.forEach { book ->
            PickBookRow(book, book.id in chosen, { onChange(if (book.id in chosen) chosen - book.id else chosen + book.id) }, indented = true)
        }
    }
}

@Composable
private fun BookPicker(state: LibraryState, chosen: Set<String>, onChange: (Set<String>) -> Unit, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<BookType?>(null) }
    val entries = remember(state.books, query, type) { pickEntries(state, query, type) }
    val shownIds = remember(entries) { entries.flatMap { it.books }.map { it.id }.toSet() }

    Column(
        Modifier
            .fillMaxSize()
            .background(EditorColors.Panel)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Choose Books", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        EditorCard("Find") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Title, author, or series") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EditorListRow("Show", BookType.entries.map { it.folderName to it }, type, "All types") { type = it }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(EditorColors.Card, RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${chosen.size} chosen", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 6.dp))
                TextButton({ onChange(chosen + shownIds) }) { Text("Select all") }
                TextButton({ onChange(chosen - shownIds) }) { Text("Clear") }
            }
            EditorDivider()
            if (entries.isEmpty()) {
                Text("No books match.", color = EditorColors.Muted, modifier = Modifier.padding(12.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.title + it.books.first().id }) { entry ->
                    when (entry) {
                        is PickEntry.OneSeries -> PickSeriesRow(entry, chosen, onChange)
                        is PickEntry.OneBook -> PickBookRow(entry.book, entry.book.id in chosen, {
                            onChange(if (entry.book.id in chosen) chosen - entry.book.id else chosen + entry.book.id)
                        })
                    }
                    EditorDivider()
                }
            }
        }
        EditorMainButton("Done", Modifier.fillMaxWidth(), onClick = onDone)
    }
}

/** Makes a shelf: a name, a style, and any books at once. Also adds or removes books later. */
@Composable
fun ShelfMakerScreen(shelf: Shelf?, state: LibraryState, onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    val styles = remember(state.userPresets) { Presets.all + state.userPresets }
    var name by remember { mutableStateOf(shelf?.name.orEmpty()) }
    var styleName by remember { mutableStateOf(styles.first().first) }
    var chosen by remember { mutableStateOf(shelf?.let { held -> state.booksOn(held.id).map { it.id }.toSet() }.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    BackHandler(enabled = picking) { picking = false }

    EditorTheme {
        if (picking) {
            BookPicker(state, chosen, { chosen = it }) { picking = false }
            return@EditorTheme
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(EditorColors.Panel)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            Text(
                if (shelf == null) "New Shelf" else "Edit Shelf",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditorCard("Name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Thrillers") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (shelf == null) {
                    EditorCard("Style") {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            styles.forEach { (label, style) ->
                                StyleTile(
                                    name = label,
                                    style = style,
                                    selected = label == styleName,
                                    modifier = Modifier.width(128.dp),
                                    onPick = { styleName = label },
                                    onDelete = null,
                                )
                            }
                        }
                    }
                }
                EditorCard(
                    "Books",
                    trailing = { EditorOutlineButton("Choose Books") { picking = true } },
                ) {
                    Text(
                        if (chosen.isEmpty()) "You can also add books later." else "${chosen.size} chosen",
                        color = EditorColors.Muted,
                    )
                }
            }
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditorOutlineButton("Cancel", Modifier.weight(1f), onDone)
                EditorMainButton(
                    label = if (shelf == null) "Create Shelf" else "Save",
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank() && !saving,
                ) {
                    saving = true
                    val style = styles.first { it.first == styleName }.second
                    // This finishes even if the screen closes, because it can touch many books.
                    appScope.launch {
                        if (shelf == null) {
                            repository.createShelf(name.trim(), style, chosen)
                        } else {
                            repository.updateShelf(shelf.id, name.trim(), chosen)
                        }
                        withContext(Dispatchers.Main) { onDone() }
                    }
                }
            }
        }
    }
}
