package com.tim.reader.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryState
import com.tim.reader.engine.NaturalOrder
import com.tim.reader.library.FolderChild
import com.tim.reader.library.LibraryFolder
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Folders the app keeps for itself, which aren't books. */
private val HIDDEN_FOLDERS = setOf("Library Data", "Theme Files")

/** A folder on the way down from the library folder. */
class FolderPlace(val name: String, val documentId: String)

/**
 * The library folder as it is on disk: Books, Comics, Manga, Manhwa and Import, then series
 * inside them. A book the library knows opens; any other file can be brought in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    state: LibraryState,
    header: HeaderActions,
    actions: BookActions,
    /** Kept by the caller, so coming back from a book returns to the same folder. */
    path: SnapshotStateList<FolderPlace>,
    onImport: (Uri) -> Unit,
) {
    val theme = state.homeTheme ?: return
    val context = LocalContext.current
    val folder = remember { LibraryFolder.get(context) }
    val tree = folder.treeUri ?: return
    val rootId = remember(tree) { DocumentsContract.getTreeDocumentId(tree) }
    if (path.firstOrNull()?.documentId != rootId) {
        // A different library folder, or the first visit: start at the top.
        SideEffect {
            path.clear()
            path.add(FolderPlace("Library", rootId))
        }
        return
    }
    val here = path.last()
    val books = remember(state.books) {
        state.books.mapNotNull { book ->
            runCatching { DocumentsContract.getDocumentId(Uri.parse(book.documentUri)) }.getOrNull()?.let { it to book }
        }.toMap()
    }
    val children by produceState<List<FolderChild>?>(null, here.documentId, state.books.size) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { folder.children(tree, here.documentId) }.getOrDefault(emptyList())
                .filterNot { it.name.startsWith(".") || (path.size == 1 && it.name in HIDDEN_FOLDERS) }
                // Folders that are books sort with the files, since they open like one.
                .sortedWith(compareBy<FolderChild> { !(it.isFolder && books[it.documentId] == null) }.thenBy(NaturalOrder) { it.name })
        }
    }
    BackHandler(enabled = path.size > 1) { path.removeAt(path.lastIndex) }

    val look = theme.cardLook
    val text = Color(look.textColor)
    val muted = text.copy(alpha = 0.7f)
    val shape = cornerShape(look.cornerDp.coerceAtMost(28f))

    ThemedBackground(theme.background, Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LibraryHeader(theme.header, header)
            Box(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                ThemedText("Files", theme.title)
            }
            // Where you are, each step tappable to go back up to it.
            Row(
                Modifier
                    .widthIn(max = MAX_WIDTH)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                path.forEachIndexed { index, place ->
                    if (index > 0) Text("›", color = Color(theme.title.color), fontSize = 18.sp)
                    Text(
                        place.name,
                        color = Color(theme.title.color),
                        fontWeight = if (index == path.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 16.sp,
                        maxLines = 1,
                        modifier = Modifier.combinedClickable(onClickLabel = "Go to ${place.name}") {
                            while (path.size > index + 1) path.removeAt(path.lastIndex)
                        },
                    )
                }
            }
            Box(
                Modifier
                    .widthIn(max = MAX_WIDTH)
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp),
            ) {
                val list = children
                Column(
                    Modifier
                        .fillMaxWidth()
                        .themedShadow(look.shadow, shape)
                        .themedFill(look.fill, shape)
                        .themedStroke(look.stroke, shape)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    when {
                        list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(look.iconColor))
                        }
                        list.isEmpty() -> Text("This folder is empty.", color = muted, modifier = Modifier.padding(12.dp))
                        else -> LazyColumn {
                            items(list, key = { it.documentId }) { child ->
                                val book = books[child.documentId]
                                FileRow(child, book, text, muted) {
                                    when {
                                        book != null -> actions.onOpen(book)
                                        child.isFolder -> path.add(FolderPlace(child.name, child.documentId))
                                        else -> onImport(DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId))
                                    }
                                }
                            }
                            item { BottomBarSpacer() }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(child: FolderChild, book: Book?, text: Color, muted: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClickLabel = if (book != null) "Open" else if (child.isFolder) "Open folder" else "Add to library", onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (book != null) {
            SmallCover(book.displayCover)
        } else {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                GlyphIcon(if (child.isFolder) Glyph.Folder else Glyph.Download, text, Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(child.name, color = text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = when {
                book != null -> listOf(book.title.takeIf { it != child.name.substringBeforeLast('.') }, "${(book.progress * 100).toInt()}% read")
                    .filterNotNull()
                    .joinToString(" · ")
                child.isFolder -> "Folder"
                else -> "Not in your library. Tap to add it."
            }
            Text(detail, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
