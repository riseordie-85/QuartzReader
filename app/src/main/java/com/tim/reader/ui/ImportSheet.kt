package com.tim.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.reader.data.BookType
import com.tim.reader.data.Shelf
import com.tim.reader.library.ImportItem
import com.tim.reader.library.ImportOptions
import com.tim.reader.library.ImportProgress
import com.tim.reader.library.ImportResult
import com.tim.reader.library.Importer
import kotlinx.coroutines.launch

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ImportSheet(
    items: List<ImportItem>,
    skipped: List<String>,
    shelves: List<Shelf>,
    startShelfId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { Importer(context) }
    val types = remember(items) { mutableStateListOf(*items.map { it.guess }.toTypedArray()) }
    var current by remember { mutableStateOf(0) }
    var applyToAll by remember { mutableStateOf(items.size > 1) }
    var shelfId by remember { mutableStateOf(startShelfId) }
    var readList by remember { mutableStateOf(false) }
    var removeOriginals by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }

    Dialog(
        onDismissRequest = { if (progress == null) onDone() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp),
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val finished = result
                val running = progress
                when {
                    finished != null -> ImportSummary(finished, skipped, onDone)
                    running != null -> {
                        Text("Importing ${running.done + 1} of ${running.total}", style = MaterialTheme.typography.titleLarge)
                        Text(running.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items.isEmpty() -> ImportSummary(ImportResult(0, 0, emptyList(), 0), skipped, onDone)
                    else -> {
                        val many = items.size > 1
                        Text(
                            if (many && applyToAll) "Import ${items.size} items" else "What is this?",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (!applyToAll || !many) {
                            if (many) Text("${current + 1} of ${items.size}")
                            Text(items[current].name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            BookType.entries.forEach { type ->
                                val selected = types[current] == type
                                val select: () -> Unit = {
                                    if (applyToAll) {
                                        types.indices.forEach { types[it] = type }
                                    } else {
                                        types[current] = type
                                    }
                                }
                                if (selected) {
                                    Button(select, contentPadding = TYPE_PADDING) { Text(type.label, maxLines = 1) }
                                } else {
                                    OutlinedButton(select, contentPadding = TYPE_PADDING) { Text(type.label, maxLines = 1) }
                                }
                            }
                        }
                        if (many) CheckRow("Same type for all", applyToAll) { applyToAll = it }

                        Text("Shelf", style = MaterialTheme.typography.titleMedium)
                        RadioRow("Automatic, by type", shelfId == null) { shelfId = null }
                        shelves.forEach { shelf ->
                            RadioRow(shelf.name, shelfId == shelf.id) { shelfId = shelf.id }
                        }
                        CheckRow("Add to Read List", readList) { readList = it }
                        CheckRow("Remove original after copying", removeOriginals) { removeOriginals = it }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onDone) { Text("Cancel") }
                            if (!applyToAll && current < items.lastIndex) {
                                Button({ current++ }) { Text("Next") }
                            } else {
                                Button({
                                    val options = ImportOptions(
                                        types = items.indices.associate { items[it] to types[it] },
                                        shelfId = shelfId,
                                        addToReadList = readList,
                                        removeOriginals = removeOriginals,
                                    )
                                    progress = ImportProgress(0, items.size, items.first().name)
                                    scope.launch {
                                        val done = importer.run(items, options) { progress = it }
                                        result = done
                                    }
                                }) { Text("Import") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Offers to add books already sitting in the library folder, such as after a reinstall. */
@Composable
fun RestoreSheet(items: List<ImportItem>, skipped: List<String>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { Importer(context) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }

    Dialog(
        onDismissRequest = { if (progress == null) onDone() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp),
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val finished = result
                val running = progress
                when {
                    finished != null -> ImportSummary(finished, skipped, onDone)
                    running != null -> {
                        Text("Adding ${running.done + 1} of ${running.total}", style = MaterialTheme.typography.titleLarge)
                        Text(running.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        val counts = items.groupingBy { it.guess }.eachCount()
                        Text("Books found in your library folder", style = MaterialTheme.typography.titleLarge)
                        Text(
                            BookType.entries.mapNotNull { type ->
                                counts[type]?.let { count -> "$count ${if (count == 1) type.label else type.folderName}" }
                            }.joinToString(", "),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Quartz Reader can add them where they are. Each book's type comes from its folder, " +
                                "and a folder inside that becomes a series. Nothing is copied or moved.",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onDone) { Text("Not now") }
                            Button({
                                val options = ImportOptions(
                                    types = items.associateWith { it.guess },
                                    shelfId = null,
                                    addToReadList = false,
                                    removeOriginals = false,
                                )
                                progress = ImportProgress(0, items.size, items.first().name)
                                scope.launch { result = importer.run(items, options) { progress = it } }
                            }) { Text("Add ${items.size}") }
                        }
                    }
                }
            }
        }
    }
}

private val TYPE_PADDING = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

@Composable
private fun ImportSummary(result: ImportResult, skipped: List<String>, onDone: () -> Unit) {
    Text("Import finished", style = MaterialTheme.typography.titleLarge)
    Text("Added ${result.imported}")
    if (result.duplicates > 0) Text("Already in your library: ${result.duplicates}")
    if (result.originalsKept > 0) Text("Originals Android wouldn't let me remove: ${result.originalsKept}")
    if (skipped.isNotEmpty()) {
        val extra = if (skipped.size > 10) " and ${skipped.size - 10} more" else ""
        Text("Skipped: ${skipped.take(10).joinToString(", ")}$extra")
    }
    result.failed.forEach { Text("Failed: $it") }
    Button(onDone) { Text("Done") }
}

@Composable
fun ImportBusy() {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Row(
                Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Checking files")
            }
        }
    }
}
