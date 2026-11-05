package com.tim.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tim.reader.ResultsLog
import com.tim.reader.engine.ImageDecode
import com.tim.reader.engine.PageSize
import com.tim.reader.engine.PageSource
import com.tim.reader.timed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ReadMode(val label: String) {
    LeftToRight("Left to right"),
    RightToLeft("Right to left"),
    Vertical("Vertical"),
}

/** A page, or one slice of a very tall page, as a row in the vertical reader. */
private data class ReaderRow(val page: Int, val top: Int = -1, val bottom: Int = -1)

private const val DEFAULT_ASPECT = 0.7f

private fun loadPage(source: PageSource, page: Int, width: Int): Bitmap? = try {
    source.render(page, width)
} catch (error: Throwable) {
    ResultsLog.add("FAILED page ${page + 1}: ${error::class.simpleName}: ${error.message}")
    null
}

@Composable
fun ImageReaderScreen(source: PageSource) {
    var mode by remember { mutableStateOf(ReadMode.LeftToRight) }
    var currentPage by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val width = constraints.maxWidth

        when (mode) {
            ReadMode.LeftToRight -> PagedReader(source, width, rightToLeft = false, currentPage) { currentPage = it }
            ReadMode.RightToLeft -> PagedReader(source, width, rightToLeft = true, currentPage) { currentPage = it }
            ReadMode.Vertical -> VerticalReader(source, width, currentPage) { currentPage = it }
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Text(
                "${currentPage + 1} / ${source.pageCount}   ${source.label(currentPage)}",
                color = Color.White,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    mode = ReadMode.entries[(mode.ordinal + 1) % ReadMode.entries.size]
                }) { Text(mode.label) }
                Button(
                    onClick = {
                        running = true
                        scope.launch {
                            runSpeedTest(source, width)
                            running = false
                        }
                    },
                    enabled = !running,
                ) { Text(if (running) "Testing" else "Speed test") }
            }
        }
    }
}

private suspend fun runSpeedTest(source: PageSource, width: Int) = withContext(Dispatchers.IO) {
    val last = source.pageCount - 1
    ResultsLog.add("Speed test: ${source.title}, width $width")
    val stops = listOf(
        "First page" to 0,
        "Middle page" to last / 2,
        "Last page" to last,
        "Back to page 2" to minOf(1, last),
        "Next page" to minOf(2, last),
    )
    for ((label, page) in stops) {
        timed(label) { loadPage(source, page, width)?.recycle() }
    }
    val heap = Runtime.getRuntime()
    val native = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    ResultsLog.add("Memory: Java ${(heap.totalMemory() - heap.freeMemory()) / (1024 * 1024)} MB, native $native MB")
}

@Composable
private fun PagedReader(
    source: PageSource,
    width: Int,
    rightToLeft: Boolean,
    startPage: Int,
    onPage: (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = startPage) { source.pageCount }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { onPage(it) }
    }
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        reverseLayout = rightToLeft,
    ) { page ->
        val bitmap by produceState<Bitmap?>(null, page, width) {
            value = withContext(Dispatchers.IO) { loadPage(source, page, width) }
        }
        val image = bitmap
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = source.label(page),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun VerticalReader(source: PageSource, width: Int, startPage: Int, onPage: (Int) -> Unit) {
    val rows = remember(source) {
        mutableStateListOf<ReaderRow>().apply { addAll(List(source.pageCount) { ReaderRow(it) }) }
    }
    val aspects = remember(source) { mutableStateMapOf<ReaderRow, Float>() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage)

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { index ->
            rows.getOrNull(index)?.let { onPage(it.page) }
        }
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { _, row -> "${row.page}:${row.top}" }) { _, row ->
            VerticalRow(source, width, row, rows, aspects)
        }
    }
}

@Composable
private fun VerticalRow(
    source: PageSource,
    width: Int,
    row: ReaderRow,
    rows: SnapshotStateList<ReaderRow>,
    aspects: SnapshotStateMap<ReaderRow, Float>,
) {
    val bitmap by produceState<Bitmap?>(null, row, width) {
        value = withContext(Dispatchers.IO) {
            if (row.top >= 0) {
                sliceRow(source, row, width)
            } else {
                val size = sizeOf(source, row.page)
                if (size != null && size.isTall && source.canSlice) {
                    withContext(Dispatchers.Main) { replaceWithSlices(rows, row, size) }
                    null
                } else {
                    loadPage(source, row.page, width)
                }
            }
        }
    }
    val image = bitmap
    LaunchedEffect(image) {
        if (image != null) aspects[row] = image.width.toFloat() / image.height
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspects[row] ?: DEFAULT_ASPECT),
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = source.label(row.page),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

private fun sizeOf(source: PageSource, page: Int): PageSize? = try {
    source.size(page)
} catch (error: Throwable) {
    ResultsLog.add("FAILED size of page ${page + 1}: ${error::class.simpleName}: ${error.message}")
    null
}

private fun sliceRow(source: PageSource, row: ReaderRow, width: Int): Bitmap? = try {
    timed("Slice at ${row.top}") { source.slice(row.page, row.top, row.bottom, width) }
} catch (error: Throwable) {
    ResultsLog.add("FAILED slice at ${row.top}: ${error::class.simpleName}: ${error.message}")
    null
}

private fun replaceWithSlices(rows: SnapshotStateList<ReaderRow>, row: ReaderRow, size: PageSize) {
    val index = rows.indexOf(row)
    if (index < 0) return
    ResultsLog.add("Tall page ${row.page + 1}: ${size.width}x${size.height}, cutting into slices")
    val slices = (0 until size.height step ImageDecode.SLICE_HEIGHT).map { top ->
        ReaderRow(row.page, top, minOf(top + ImageDecode.SLICE_HEIGHT, size.height))
    }
    rows.removeAt(index)
    rows.addAll(index, slices)
}
