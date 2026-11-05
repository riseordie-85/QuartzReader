package com.tim.reader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.tim.reader.ResultsLog
import com.tim.reader.engine.ImageDecode
import com.tim.reader.engine.PageSize
import com.tim.reader.engine.PageSource
import com.tim.reader.engine.isWidePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private const val MEMORY_BYTES = 192 * 1024 * 1024
private const val MAX_CACHE_BYTES = 512L * 1024 * 1024

/** Loads pages for the reader, keeps recent ones in memory, and reads ahead of the page on screen. */
class PageLoader(context: Context, private val source: PageSource, cacheKey: String?) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val decoding = Dispatchers.IO.limitedParallelism(2)
    private val sliceFolder = cacheKey?.let { File(File(context.cacheDir, "tall"), it) }
    private val wideFile = cacheKey?.let { File(File(context.filesDir, "page-info"), "$it.txt") }
    private val preparing = ConcurrentHashMap.newKeySet<Int>()

    private fun pageKey(page: Int, width: Int) = "p$page:$width"

    private fun sliceKey(page: Int, top: Int, width: Int) = "s$page:$top:$width"

    private fun sliceFile(page: Int, top: Int) = sliceFolder?.let { File(it, "$page-$top.jpg") }

    fun cachedPage(page: Int, width: Int): Bitmap? = memory.get(pageKey(page, width))

    fun cachedSlice(page: Int, top: Int, width: Int): Bitmap? = memory.get(sliceKey(page, top, width))

    suspend fun page(page: Int, width: Int): Bitmap? {
        val key = pageKey(page, width)
        memory.get(key)?.let { return it }
        return withContext(decoding) {
            memory.get(key) ?: guarded("page ${page + 1}") { source.render(page, width) }?.also { memory.put(key, it) }
        }
    }

    suspend fun slice(page: Int, top: Int, bottom: Int, width: Int): Bitmap? {
        val key = sliceKey(page, top, width)
        memory.get(key)?.let { return it }
        return withContext(decoding) {
            memory.get(key) ?: guarded("slice at $top") { readSlice(page, top, bottom, width) }?.also { memory.put(key, it) }
        }
    }

    private fun readSlice(page: Int, top: Int, bottom: Int, width: Int): Bitmap? {
        val file = sliceFile(page, top)
        if (file != null && file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        return source.slice(page, top, bottom, width)
    }

    suspend fun size(page: Int): PageSize? = withContext(decoding) {
        guarded("size of page ${page + 1}") { source.size(page) }
    }

    /** Reads the pages around the one on screen, so turning a page finds them ready. */
    suspend fun readAhead(pages: List<Int>, width: Int) {
        for (page in pages) {
            if (page !in 0 until source.pageCount) continue
            coroutineContext.ensureActive()
            page(page, width)
        }
    }

    /**
     * Formats that must be read from the top get slower with depth. Decoding the page once and saving
     * its slices makes every later read take the same time. Reads work normally until this finishes.
     */
    fun prepareTallPage(scope: CoroutineScope, page: Int, size: PageSize) {
        val folder = sliceFolder ?: return
        if (File(folder, "$page.done").exists() || !preparing.add(page)) return
        scope.launch(Dispatchers.IO) {
            try {
                folder.mkdirs()
                pruneCache(folder.parentFile)
                val full = source.fullForSlicing(page) ?: return@launch
                try {
                    var top = 0
                    while (top < size.height) {
                        ensureActive()
                        val bottom = minOf(top + ImageDecode.SLICE_HEIGHT, size.height)
                        val slice = Bitmap.createBitmap(full, 0, top, full.width, bottom - top)
                        val partial = File(folder, "$page-$top.tmp")
                        partial.outputStream().use { slice.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        slice.recycle()
                        partial.renameTo(File(folder, "$page-$top.jpg"))
                        top = bottom
                    }
                    File(folder, "$page.done").createNewFile()
                } finally {
                    full.recycle()
                }
            } catch (error: OutOfMemoryError) {
                // The page stays readable through the slower route.
                ResultsLog.add("Not enough memory to cache tall page ${page + 1}")
            } catch (error: java.io.IOException) {
                ResultsLog.add("Couldn't cache tall page ${page + 1}: ${error.message}")
            } finally {
                preparing.remove(page)
            }
        }
    }

    /** Removes the oldest books' slices once the cache passes its limit. */
    private fun pruneCache(root: File?) {
        val books = root?.listFiles()?.toList().orEmpty()
        var total = books.sumOf { book -> book.listFiles()?.sumOf { it.length() } ?: 0L }
        for (book in books.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= book.listFiles()?.sumOf { it.length() } ?: 0L
            book.deleteRecursively()
        }
    }

    /** Pages wide enough to fill the screen alone, saved so the scan only ever runs once per book. */
    suspend fun widePages(onUpdate: (Set<Int>) -> Unit) = withContext(Dispatchers.IO) {
        val saved = wideFile?.takeIf { it.exists() }?.readText()?.split(',')?.mapNotNull { it.toIntOrNull() }
        if (saved != null) {
            onUpdate(saved.toSet())
            return@withContext
        }
        val wide = HashSet<Int>()
        for (page in 0 until source.pageCount) {
            ensureActive()
            val found = guarded("size of page ${page + 1}") { source.size(page) }
            if (found != null && isWidePage(found) && wide.add(page)) onUpdate(wide.toSet())
        }
        wideFile?.let {
            it.parentFile?.mkdirs()
            it.writeText(wide.joinToString(","))
        }
    }

    private inline fun <T> guarded(label: String, block: () -> T?): T? = try {
        block()
    } catch (error: Exception) {
        ResultsLog.add("FAILED $label: ${error::class.simpleName}: ${error.message}")
        null
    } catch (error: OutOfMemoryError) {
        ResultsLog.add("FAILED $label: out of memory")
        null
    }
}
