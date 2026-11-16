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
import com.tim.reader.library.DeviceSettings

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

/**
 * The first tap shows a title's details. In the split view they're on the right, and a second tap
 * on the title opens it. Otherwise a book's show in a popup with an Open button, unless that's turned
 * off in Settings.
 * A series opens its page straight away, since that page is its details.
 */
fun tapItem(item: ShelfItem, selection: CoverSelection?, peek: BookPeek?, actions: BookActions) {
    when {
        selection != null && selection.key != item.key -> selection.key = item.key
        selection == null && peek != null && item is ShelfItem.Single && DeviceSettings.bookPopup -> peek.bookId = item.book.id
        else -> item.activate(actions)
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
