package com.tim.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tim.reader.data.ShelfItem

/** Which title the split view is showing. Shelf tiles and the large cover both follow it. */
class CoverSelection {
    var key by mutableStateOf<String?>(null)
}

/** Null when the split view isn't showing, so a tap opens the book straight away. */
val LocalCoverSelection = compositionLocalOf<CoverSelection?> { null }

fun ShelfItem.activate(actions: BookActions) {
    when (this) {
        is ShelfItem.Single -> actions.onOpen(book)
        is ShelfItem.Stack -> actions.onSeries(series.id)
    }
}

/** The first tap shows a title on the right, and a second tap on it opens it. */
fun tapItem(item: ShelfItem, selection: CoverSelection?, actions: BookActions) {
    if (selection == null || selection.key == item.key) {
        item.activate(actions)
    } else {
        selection.key = item.key
    }
}

/** Scrolls the shelf so the highlighted title stays on screen while the covers are swiped. */
@Composable
fun FollowSelection(
    items: List<ShelfItem>,
    perRow: Int,
    isVisible: (Int) -> Boolean,
    scrollTo: suspend (Int) -> Unit,
) {
    val selection = LocalCoverSelection.current ?: return
    val latestItems by rememberUpdatedState(items)
    LaunchedEffect(selection, perRow) {
        snapshotFlow { selection.key }.collect { key ->
            val index = latestItems.indexOfFirst { it.key == key }
            if (index >= 0 && !isVisible(index / perRow)) scrollTo(index / perRow)
        }
    }
}
