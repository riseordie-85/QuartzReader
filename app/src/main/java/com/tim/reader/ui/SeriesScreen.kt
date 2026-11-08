package com.tim.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tim.reader.data.Book
import com.tim.reader.data.Series
import com.tim.reader.data.ShelfItem
import com.tim.reader.data.ViewMode
import com.tim.reader.data.nextVolume
import com.tim.reader.data.volumeOrder
import com.tim.reader.library.DeviceSettings
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText

@Composable
fun SeriesScreen(
    series: Series,
    volumes: List<Book>,
    theme: ShelfTheme,
    header: HeaderActions,
    actions: BookActions,
    onPageSettings: () -> Unit,
    onFindInfo: () -> Unit,
    onEdit: () -> Unit,
) {
    val ordered = volumes.sortedWith(volumeOrder)
    val rowKey = booksPerRowKey("series", ViewMode.Shelf)
    var choosingRow by remember { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(1) }
    if (choosingRow) BooksPerRowDialog(rowKey, automatic) { choosingRow = false }
    CoverSplit(ordered.map { ShelfItem.Single(it) }, theme, actions, series.id) { pageModifier ->
    ThemedBackground(theme.background, pageModifier) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LibraryHeader(theme.header, header)
            ThemedText(series.title, theme.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Row(
                Modifier
                    .padding(horizontal = 24.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ordered.isNotEmpty()) {
                    Button(onClick = { actions.onOpen(nextVolume(ordered)) }) { Text("Continue") }
                }
                Button(onClick = onEdit) { Text("Edit series") }
                Button(onClick = onPageSettings) { Text("Page settings") }
                Button(onClick = onFindInfo) { Text("Find info") }
                Button(onClick = { choosingRow = true }) { Text("Books per row") }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val auto = columnsFor(maxWidth.value)
                val columns = DeviceSettings.booksPerRow(rowKey) ?: auto
                SideEffect { automatic = auto }
                val items = ordered.map { ShelfItem.Single(it) }
                val list = rememberLazyListState()
                FollowSelection(
                    items,
                    columns,
                    { row -> list.layoutInfo.visibleItemsInfo.any { it.index == row } },
                    { row -> list.animateScrollToItem(row) },
                )
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    shelfRows(items, columns, theme, actions)
                }
            }
        }
    }
    }
}
