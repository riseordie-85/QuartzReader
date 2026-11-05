package com.tim.reader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tim.reader.EpubActivity
import com.tim.reader.OpenEpub
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.ShelfItem
import com.tim.reader.data.SortOrder
import com.tim.reader.data.shelfItems
import com.tim.reader.engine.Kind
import com.tim.reader.engine.PageSource
import com.tim.reader.library.ImportItem
import com.tim.reader.library.Importer
import com.tim.reader.library.LibraryFolder
import com.tim.reader.openBookSource
import com.tim.reader.openEpub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Route {
    data object Home : Route
    data class ShelfPages(val shelfId: String) : Route
    data object RecentlyRead : Route
    data object ReadList : Route
    data object AllBooks : Route
    data object Shelves : Route
    data object Settings : Route
    data object Tools : Route
    data class SeriesPage(val seriesId: String) : Route
    data class Details(val bookId: String) : Route
    data class Reader(val book: Book) : Route
}

private class PendingImport(val items: List<ImportItem>, val skipped: List<String>)

@Composable
fun ReaderApp(incoming: List<Uri>, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val folder = remember { LibraryFolder.get(context) }
    val repository = remember { LibraryRepository.get(context) }
    var folderReady by remember { mutableStateOf(folder.isReady()) }
    val loaded by produceState(false) {
        repository.load()
        value = true
    }

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            folder.choose(uri)
            folderReady = folder.isReady()
        }
    }

    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !folderReady -> Welcome { choose.launch(null) }
        else -> Library(repository, incoming, onIncomingHandled)
    }
}

@Composable
private fun Welcome(onChoose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Quartz Reader", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Choose a folder for your library. Quartz Reader keeps your books there and organizes them for you, so they stay safe if the app is ever removed.",
            textAlign = TextAlign.Center,
        )
        Button(onChoose) { Text("Choose library folder") }
    }
}

private suspend fun openBook(context: Context, book: Book, push: (Route) -> Unit) {
    when {
        book.format == Kind.MOBI.name ->
            Toast.makeText(context, "MOBI conversion arrives in Phase 7", Toast.LENGTH_LONG).show()
        book.format == Kind.EPUB.name -> {
            try {
                val publication = withContext(Dispatchers.IO) { openEpub(context, Uri.parse(book.documentUri)) }
                OpenEpub.publication = publication
                OpenEpub.bookId = book.id
                OpenEpub.locator = book.locator
                context.startActivity(Intent(context, EpubActivity::class.java))
            } catch (error: Exception) {
                Toast.makeText(context, "Couldn't open this book: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        else -> push(Route.Reader(book))
    }
}

@Composable
private fun Library(repository: LibraryRepository, incoming: List<Uri>, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsState()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var chooser by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }

    fun scan(files: List<Uri>, folders: List<Uri>) {
        if (files.isEmpty() && folders.isEmpty()) return
        scanning = true
        scope.launch {
            val (items, skipped) = Importer(context).scan(files, folders)
            scanning = false
            pending = PendingImport(items, skipped)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        scan(uris, emptyList())
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scan(emptyList(), listOf(uri))
    }

    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) {
            scan(incoming, emptyList())
            onIncomingHandled()
        }
    }

    fun push(route: Route) {
        stack.add(route)
    }

    val header = HeaderActions(
        onHome = {
            stack.clear()
            stack.add(Route.Home)
        },
        onImport = { chooser = true },
        onShelves = { push(Route.Shelves) },
        onSettings = { push(Route.Settings) },
    )
    val actions = BookActions(
        onOpen = { book -> scope.launch { openBook(context, book, ::push) } },
        onDetails = { book -> push(Route.Details(book.id)) },
        onSeries = { id -> push(Route.SeriesPage(id)) },
    )

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    when (val route = stack.last()) {
        Route.Home -> HomeScreen(
            state = state,
            header = header,
            actions = actions,
            onRecentlyRead = { push(Route.RecentlyRead) },
            onReadList = { push(Route.ReadList) },
            onShelf = { push(Route.ShelfPages(it)) },
        )
        is Route.ShelfPages -> ShelfPager(state, route.shelfId, header, actions)
        Route.RecentlyRead -> SystemList("Recently Read", state.recentlyRead, state, header, actions, keepOrder = true)
        Route.ReadList -> SystemList("Read List", state.readList, state, header, actions, keepOrder = true)
        Route.AllBooks -> SystemList("All Books", state.books, state, header, actions, keepOrder = false)
        Route.Shelves -> ShelvesListScreen(
            state = state,
            onAllBooks = { push(Route.AllBooks) },
            onShelf = { push(Route.ShelfPages(it)) },
        )
        Route.Settings -> SettingsScreen(onTestTools = { push(Route.Tools) })
        Route.Tools -> TestToolsScreen(onExit = { stack.removeAt(stack.lastIndex) })
        is Route.SeriesPage -> {
            val series = state.seriesFor(route.seriesId)
            val theme = state.homeTheme
            if (series != null && theme != null) {
                SeriesScreen(series, state.books.filter { it.seriesId == series.id }, theme, header, actions)
            }
        }
        is Route.Details -> {
            val book = state.book(route.bookId)
            if (book != null) {
                BookDetailsScreen(
                    book = book,
                    state = state,
                    onOpen = { scope.launch { openBook(context, book, ::push) } },
                    onGone = { stack.remove(route) },
                )
            }
        }
        is Route.Reader -> BookReader(route.book, repository)
    }

    if (chooser) {
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Import") },
            text = { Text("Choose files, or a folder of images or volumes.") },
            confirmButton = {
                TextButton({
                    chooser = false
                    pickFiles.launch(arrayOf("*/*"))
                }) { Text("Files") }
            },
            dismissButton = {
                TextButton({
                    chooser = false
                    pickFolder.launch(null)
                }) { Text("Folder") }
            },
        )
    }
    if (scanning) ImportBusy()
    pending?.let { waiting ->
        val startShelf = (stack.last() as? Route.ShelfPages)?.shelfId
        ImportSheet(waiting.items, waiting.skipped, state.shelves, startShelf) { pending = null }
    }
}

@Composable
private fun SystemList(
    title: String,
    books: List<Book>,
    state: LibraryState,
    header: HeaderActions,
    actions: BookActions,
    keepOrder: Boolean,
) {
    val theme = state.homeTheme ?: return
    val items = if (keepOrder) {
        books.map { ShelfItem.Single(it) }
    } else {
        shelfItems(books, SortOrder.Title, false, state)
    }
    ListPage(title, theme, items, header, actions)
}

@Composable
private fun BookReader(book: Book, repository: LibraryRepository) {
    val context = LocalContext.current
    val opened by produceState<Result<PageSource>?>(null, book.id) {
        value = withContext(Dispatchers.IO) { runCatching { openBookSource(context, book) } }
    }
    val result = opened
    val source = result?.getOrNull()
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        source == null -> Text(
            "Couldn't open this book: ${result.exceptionOrNull()?.message}",
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(24.dp),
        )
        else -> {
            DisposableEffect(source) {
                onDispose { appScope.launch { source.close() } }
            }
            ImageReaderScreen(
                source = source,
                initialPage = book.position.coerceIn(0, (source.pageCount - 1).coerceAtLeast(0)),
                initialDirection = book.direction,
                onPage = { page ->
                    val total = (source.pageCount - 1).coerceAtLeast(1)
                    appScope.launch { repository.savePosition(book.id, page, page.toFloat() / total) }
                },
            )
        }
    }
}
