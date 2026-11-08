package com.tim.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryRepository
import com.tim.reader.engine.Kind
import com.tim.reader.engine.PageSource
import com.tim.reader.library.COVER_WIDTH
import com.tim.reader.library.Covers
import com.tim.reader.openBookSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val THUMBNAIL_WIDTH = 240
private const val PICKED_MAX_SIDE = 1600

/** Ways to change a book's cover: turn it, use one of its pages, or use a picture from the phone. */
@Composable
fun CoverOptionsDialog(book: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var choosingPage by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            changeCover(context, book.id) { decodePicked(context, uri) }
            onDismiss()
        }
    }
    val hasPages = book.format != Kind.EPUB.name && book.format != Kind.MOBI.name

    if (choosingPage) {
        PagePicker(book, onDismiss = { choosingPage = false }) { page ->
            changeCover(context, book.id, needsPages = true) { source -> source?.let { renderCoverPage(it, page) } }
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change cover") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        changeCover(context, book.id) { rotated(context, book.id, -90f) }
                        onDismiss()
                    }) { Text("Rotate left") }
                    TextButton({
                        changeCover(context, book.id) { rotated(context, book.id, 90f) }
                        onDismiss()
                    }) { Text("Rotate right") }
                }
                if (hasPages) TextButton({ choosingPage = true }) { Text("Choose a page") }
                TextButton({
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose an image") }
                if (book.customCoverPath != null) {
                    TextButton({
                        appScope.launch {
                            val repository = LibraryRepository.get(context)
                            val old = repository.state.value.book(book.id)?.customCoverPath
                            repository.setCustomCover(book.id, null)
                            Covers.deleteCustom(old)
                        }
                        onDismiss()
                    }) { Text("Use the original cover") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * Makes a new cover and saves it as the book's custom cover, replacing any earlier one. The work
 * outlives the dialog. [make] gets the book's pages when it needs them, opened just for this.
 */
// Cover changes run one at a time, so a second change starts from the first one's result.
private val coverWork = Dispatchers.IO.limitedParallelism(1)

private fun changeCover(context: Context, bookId: String, needsPages: Boolean = false, make: suspend (PageSource?) -> Bitmap?) {
    val appContext = context.applicationContext
    appScope.launch(coverWork) {
        val repository = LibraryRepository.get(appContext)
        val book = repository.state.value.book(bookId) ?: return@launch
        val path = try {
            val source = if (needsPages) openBookSource(appContext, book) else null
            val cover = try {
                make(source)
            } finally {
                source?.close()
            }
            cover?.let { Covers.saveCustom(appContext, bookId, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
        if (path == null) {
            withContext(Dispatchers.Main) { Toast.makeText(appContext, "Couldn't change the cover", Toast.LENGTH_LONG).show() }
            return@launch
        }
        val old = repository.state.value.book(bookId)?.customCoverPath
        repository.setCustomCover(bookId, path)
        Covers.deleteCustom(old)
    }
}

private fun renderCoverPage(source: PageSource, page: Int): Bitmap? = renderTop(source, page, COVER_WIDTH * 2)

/**
 * A page, or just its top for a tall webtoon page, like the automatic covers. Tall pages are cut
 * before decoding where the format allows, so a long strip never sits whole in memory.
 */
private fun renderTop(source: PageSource, page: Int, width: Int): Bitmap? {
    val size = runCatching { source.size(page) }.getOrNull()
    if (size != null && source.canSlice && size.height > size.width * 3 / 2) {
        runCatching { source.slice(page, 0, size.width * 3 / 2, width) }.getOrNull()?.let { return it }
    }
    val full = source.render(page, width) ?: return null
    return if (full.height > full.width * 3 / 2) Bitmap.createBitmap(full, 0, 0, full.width, full.width * 3 / 2) else full
}

private fun rotated(context: Context, bookId: String, degrees: Float): Bitmap? {
    val path = LibraryRepository.get(context).state.value.book(bookId)?.displayCover ?: return null
    val original = BitmapFactory.decodeFile(path) ?: return null
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
}

/** Opens a picked picture the right way up and no bigger than a cover needs. */
private fun decodePicked(context: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        var sample = 1
        while (longest / (sample * 2) >= PICKED_MAX_SIDE) sample *= 2
        decoder.setTargetSampleSize(sample)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

/** A grid of the book's pages to choose a cover from. */
@Composable
private fun PagePicker(book: Book, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    // One page at a time, so a big archive doesn't decode dozens of pages at once.
    val renderer: CoroutineDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }
    val opened by produceState<Result<PageSource>?>(null, book.id) {
        // Not cancellable, so a book still opening when the picker closes is closed rather than left open.
        val result = withContext(NonCancellable + Dispatchers.IO) { runCatching { openBookSource(context, book) } }
        value = result
        // Closing waits for any thumbnail still drawing.
        awaitDispose { result.getOrNull()?.let { source -> appScope.launch(renderer) { source.close() } } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose a page", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text("Cancel") }
                }
                val result = opened
                val source = result?.getOrNull()
                when {
                    result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    source == null -> Text("Couldn't open this book: ${result.exceptionOrNull()?.message}")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items((0 until source.pageCount).toList(), key = { it }) { page ->
                            PageThumbnail(source, page, renderer) { onPick(page) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageThumbnail(source: PageSource, page: Int, renderer: CoroutineDispatcher, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, source, page) {
        value = withContext(renderer) { runCatching { renderTop(source, page, THUMBNAIL_WIDTH) }.getOrNull()?.asImageBitmap() }
    }
    Column(
        Modifier.clickable(onClickLabel = "Use page ${page + 1}", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            val shown = image
            if (shown != null) {
                Image(shown, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.padding(40.dp))
            }
        }
        Text("${page + 1}", style = MaterialTheme.typography.bodySmall)
    }
}
