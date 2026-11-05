package com.tim.reader.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Every fixed-page format looks like this to the reader. */
interface PageSource : Closeable {
    val title: String
    val pageCount: Int
    val canSlice: Boolean
    fun label(page: Int): String
    fun size(page: Int): PageSize?
    fun render(page: Int, targetWidth: Int): Bitmap?
    fun slice(page: Int, top: Int, bottom: Int, targetWidth: Int): Bitmap?
}

/** Sources whose pages are image files. */
abstract class ImagePageSource : PageSource {
    private val sizes = ConcurrentHashMap<Int, PageSize>()
    private var regionPage = -1
    private var regions: BitmapRegionDecoder? = null

    protected abstract fun bytes(page: Int): ByteArray

    override val canSlice get() = true

    override fun size(page: Int): PageSize? =
        sizes[page] ?: ImageDecode.size(bytes(page))?.also { sizes[page] = it }

    override fun render(page: Int, targetWidth: Int): Bitmap? = ImageDecode.full(bytes(page), targetWidth)

    override fun slice(page: Int, top: Int, bottom: Int, targetWidth: Int): Bitmap? = synchronized(this) {
        if (regionPage != page) {
            regions?.recycle()
            regions = null
            regionPage = -1
            regions = ImageDecode.openRegions(bytes(page))
            regionPage = page
        }
        ImageDecode.region(regions!!, top, bottom, targetWidth)
    }

    override fun close() {
        synchronized(this) {
            regions?.recycle()
            regions = null
            regionPage = -1
        }
    }
}

/** Keeps recently read pages in memory, up to a fixed number of bytes. */
private class ByteCache(private val maxBytes: Long) {
    private val entries = LinkedHashMap<Int, ByteArray>(16, 0.75f, true)
    private var total = 0L

    fun get(key: Int): ByteArray? = entries[key]

    fun put(key: Int, value: ByteArray) {
        entries.put(key, value)?.let { total -= it.size }
        total += value.size
        val oldest = entries.entries.iterator()
        while (total > maxBytes && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) continue
            total -= entry.value.size
            oldest.remove()
        }
    }

    fun clear() {
        entries.clear()
        total = 0
    }
}

/**
 * Reads CBZ, CBR and CB7 through libarchive.
 *
 * Solid archives can only be read from the start, so one reader stays open and moves forward. Moving
 * backward reopens it. In those formats, every page passed on the way is kept in the cache.
 */
class ArchivePageSource(
    context: Context,
    uri: Uri,
    override val title: String,
    private val kind: Kind,
) : ImagePageSource() {

    private class Cursor(address: Long, size: Long) : Closeable {
        private val archive = Archive.readNew()
        var ordinal = -1
            private set
        private var entry = 0L
        private val scratch = ArchiveEntry.new2(archive)

        init {
            try {
                Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
                Archive.readOpenMemoryUnsafe(archive, address, size)
            } catch (error: Exception) {
                ArchiveEntry.free(scratch)
                Archive.free(archive)
                throw error
            }
        }

        fun next(): Boolean {
            // A warning still leaves a usable entry, and scratch keeps it through the exception.
            entry = try {
                Archive.readNextHeader2(archive, scratch)
            } catch (error: ArchiveException) {
                if (error.code != Archive.ERRNO_WARN) throw error
                scratch
            }
            if (entry == 0L) return false
            ordinal++
            return true
        }

        val isFile get() = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
        val path get() = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString().orEmpty()
        val entrySize get() = ArchiveEntry.size(entry)

        fun readCurrent(): ByteArray {
            var data = ByteArray((entrySize + 1).coerceAtLeast(64L * 1024).toInt())
            var length = 0
            while (true) {
                if (length == data.size) data = data.copyOf(data.size * 2)
                val buffer = ByteBuffer.wrap(data, length, data.size - length)
                Archive.readData(archive, buffer)
                val read = buffer.position() - length
                if (read <= 0) break
                length += read
            }
            return if (length == data.size) data else data.copyOf(length)
        }

        override fun close() {
            ArchiveEntry.free(scratch)
            Archive.free(archive)
        }
    }

    private val descriptor: ParcelFileDescriptor
    private val address: Long
    private val mappedSize: Long
    private val temporaryCopy: File?
    private val listing: PageListing
    private val passThrough = kind == Kind.SEVEN_ZIP || kind == Kind.RAR4 || kind == Kind.RAR5
    private val cache = ByteCache(192L * 1024 * 1024)
    private var cursor: Cursor? = null
    private var closed = false

    init {
        var copy: File? = null
        var opened = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Android returned no file for this document")
        var mapped = mapOrZero(opened)
        if (mapped == 0L) {
            // Files from cloud providers can't be mapped, so they're read from a local copy.
            opened.close()
            copy = copyToCache(context.contentResolver, uri, context.cacheDir)
            opened = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            mapped = Os.mmap(0, opened.statSize, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, opened.fileDescriptor, 0)
        }
        descriptor = opened
        address = mapped
        mappedSize = opened.statSize
        temporaryCopy = copy
        listing = try {
            Cursor(address, mappedSize).use { index ->
                val entries = ArrayList<RawEntry>()
                while (index.next()) {
                    if (index.isFile) entries += RawEntry(index.ordinal, index.path, index.entrySize)
                }
                buildPageListing(entries)
            }
        } catch (error: Exception) {
            Os.munmap(address, mappedSize)
            descriptor.close()
            temporaryCopy?.delete()
            throw error
        }
    }

    private fun dropCursor() {
        val old = cursor
        cursor = null
        old?.close()
    }

    private fun mapOrZero(file: ParcelFileDescriptor): Long {
        if (file.statSize <= 0) return 0
        return try {
            Os.mmap(0, file.statSize, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, file.fileDescriptor, 0)
        } catch (_: ErrnoException) {
            0
        }
    }

    private fun copyToCache(resolver: ContentResolver, uri: Uri, folder: File): File {
        val target = File.createTempFile("book", ".tmp", folder)
        resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private val pageOrdinals by lazy { listing.pages.mapTo(HashSet()) { it.ordinal } }

    val chapterCount get() = listing.pages.map { it.chapter }.distinct().size
    val duplicateNested get() = listing.duplicateNested
    val unreadNested get() = listing.unreadNested

    override val pageCount get() = listing.pages.size

    override fun label(page: Int) = listing.pages[page].path.substringAfterLast('/')

    override fun bytes(page: Int): ByteArray {
        return synchronized(this) {
            // A page may still be loading when the reader closes, and the mapped file is gone by then.
            check(!closed) { "This book is closed" }
            val ordinal = listing.pages[page].ordinal
            cache.get(ordinal)?.let { return it }

            val current = cursor?.takeIf { it.ordinal < ordinal } ?: run {
                dropCursor()
                Cursor(address, mappedSize).also { cursor = it }
            }
            try {
                while (current.next()) {
                    if (current.ordinal == ordinal) {
                        return current.readCurrent().also { cache.put(ordinal, it) }
                    }
                    if (passThrough && current.ordinal in pageOrdinals) {
                        cache.put(current.ordinal, current.readCurrent())
                    }
                }
            } catch (error: Exception) {
                // libarchive can't continue after a failure, so the next page starts fresh.
                runCatching { dropCursor() }
                throw error
            }
            error("Page $page is missing from the archive")
        }
    }

    override fun close() {
        super.close()
        synchronized(this) {
            if (closed) return
            closed = true
            dropCursor()
            cache.clear()
            Os.munmap(address, mappedSize)
            descriptor.close()
            temporaryCopy?.delete()
        }
    }
}

/** Reads a chosen folder of images, including one level of chapter folders. */
class FolderPageSource(context: Context, tree: Uri, override val title: String) : ImagePageSource() {
    private val resolver = context.contentResolver
    private val documents = HashMap<Int, Uri>()
    private val listing: PageListing

    init {
        val found = ArrayList<RawEntry>()
        fun walk(parentId: String, prefix: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(children, columns, null, null, null)?.use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0)
                    val name = rows.getString(1)
                    if (rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(id, "$prefix$name/", depth + 1)
                    } else {
                        documents[found.size] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found += RawEntry(found.size, prefix + name, 0)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        listing = buildPageListing(found)
    }

    override val pageCount get() = listing.pages.size

    override fun label(page: Int) = listing.pages[page].path.substringAfterLast('/')

    override fun bytes(page: Int): ByteArray {
        val document = documents.getValue(listing.pages[page].ordinal)
        return resolver.openInputStream(document)!!.use { it.readBytes() }
    }
}

/** PDFs drawn by Android's own renderer. */
class PdfPageSource(context: Context, uri: Uri, override val title: String) : PageSource {
    private val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Android returned no file for this document")
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        throw error
    }
    private val sizes = ConcurrentHashMap<Int, PageSize>()

    override val pageCount get() = renderer.pageCount

    override val canSlice get() = false

    override fun label(page: Int) = "Page ${page + 1}"

    override fun size(page: Int): PageSize = sizes.getOrPut(page) {
        synchronized(renderer) {
            renderer.openPage(page).use { PageSize(it.width, it.height) }
        }
    }

    override fun render(page: Int, targetWidth: Int): Bitmap = synchronized(renderer) {
        renderer.openPage(page).use { pdfPage ->
            var width = targetWidth
            var height = (width.toLong() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
            while (!ImageDecode.fits(width, height)) {
                width /= 2
                height /= 2
            }
            val bitmap = Bitmap.createBitmap(width, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun slice(page: Int, top: Int, bottom: Int, targetWidth: Int): Bitmap = render(page, targetWidth)

    override fun close() {
        synchronized(renderer) { renderer.close() }
        descriptor.close()
    }
}
