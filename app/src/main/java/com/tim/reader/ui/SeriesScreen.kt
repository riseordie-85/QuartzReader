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
import androidx.compose.ui.Alignment
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
    onEdit: () -> Unit,
) {
    val ordered = volumes.sortedWith(volumeOrder)
    val rowKey = booksPerRowKey("series", ViewMode.Shelf)
    var automatic by remember { mutableStateOf(1) }
    CoverSplit(ordered.map { ShelfItem.Single(it) }, theme, actions, series.id) { pageModifier ->
    ThemedBackground(theme.background, pageModifier) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LibraryHeader(theme.header, header)
            TitleWithMenu(
                title = series.title,
                theme = theme,
                menu = TitleMenu(
                    buttons = listOf("Edit Series" to onEdit, "Page Settings" to onPageSettings),
                    rowKey = rowKey,
                    automaticRow = automatic,
                ),
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
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
                // Room at the end, so the last row can scroll clear of Continue.
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 96.dp)) {
                    shelfRows(items, columns, theme, actions)
                }
            }
        }
        if (ordered.isNotEmpty()) {
            ContinuePill(
                theme.button,
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 20.dp, bottom = 20.dp),
            ) { actions.onOpen(nextVolume(ordered)) }
        }
    }
    }
}
