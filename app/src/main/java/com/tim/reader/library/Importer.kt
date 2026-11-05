package com.tim.reader.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import com.tim.reader.engine.ArchivePageSource
import com.tim.reader.engine.FolderPageSource
import com.tim.reader.engine.Kind
import com.tim.reader.engine.PageSource
import com.tim.reader.engine.PdfPageSource
import com.tim.reader.engine.sniff
import com.tim.reader.openEpub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.cover
import java.util.UUID

private val imageNames = Regex("""\.(jpe?g|png|webp|gif|bmp|avif|heic|jp2|j2k|jpx|jpf)$""", RegexOption.IGNORE_CASE)
private val manhwaNames = Regex("""manhwa|webtoon""", RegexOption.IGNORE_CASE)
private val mangaNames = Regex("""manga""", RegexOption.IGNORE_CASE)

/** One book waiting to be imported. */
data class ImportItem(
    val uri: Uri,
    val name: String,
    val kind: Kind?,
    val guess: BookType,
    val folderTree: Uri? = null,
    val folderDocumentId: String? = null,
    val seriesHint: String? = null,
) {
    val isFolder get() = folderTree != null
}

/** The choices from the import sheet. A null shelf files each book on the shelf for its type. */
data class ImportOptions(
    val types: Map<ImportItem, BookType>,
    val shelfId: String?,
    val addToReadList: Boolean,
    val removeOriginals: Boolean,
)

data class ImportProgress(val done: Int, val total: Int, val current: String)

data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: List<String>,
    val originalsKept: Int,
)

private data class Details(
    val title: String,
    val author: String,
    val series: String?,
    val number: Double?,
    val pageCount: Int,
    val cover: android.graphics.Bitmap?,
)

class Importer(context: Context) {
    private val appContext = context.applicationContext
    private val folder = LibraryFolder.get(appContext)
    private val repository = LibraryRepository.get(appContext)

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
                if (rows.moveToFirst()) rows.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    private fun readKind(uri: Uri, name: String): Kind {
        val head = ByteArray(128)
        val length = appContext.contentResolver.openInputStream(uri)?.use { it.readNBytes(head, 0, head.size) } ?: 0
        return sniff(head.copyOf(length), name)
    }

    private fun guessType(name: String, kind: Kind?): BookType = when {
        kind == null || manhwaNames.containsMatchIn(name) -> BookType.Manhwa
        mangaNames.containsMatchIn(name) -> BookType.Manga
        kind == Kind.EPUB || kind == Kind.MOBI || kind == Kind.PDF -> BookType.Book
        else -> BookType.Comic
    }

    /** Works out what each picked file or folder is. Returns what can be imported, and what can't. */
    suspend fun scan(files: List<Uri>, folders: List<Uri>): Pair<List<ImportItem>, List<String>> =
        withContext(Dispatchers.IO) {
            val items = ArrayList<ImportItem>()
            val skipped = ArrayList<String>()
            files.forEach { uri -> addFile(uri, displayName(uri), null, items, skipped) }
            folders.forEach { tree -> scanFolder(tree, items, skipped) }
            items to skipped
        }

    private fun addFile(uri: Uri, name: String, series: String?, items: MutableList<ImportItem>, skipped: MutableList<String>) {
        val kind = runCatching { readKind(uri, name) }.getOrDefault(Kind.UNKNOWN)
        if (kind == Kind.UNKNOWN) {
            skipped += name
        } else {
            items += ImportItem(uri, name, kind, guessType(name, kind), seriesHint = series)
        }
    }

    private fun scanFolder(tree: Uri, items: MutableList<ImportItem>, skipped: MutableList<String>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':')
        fun folderItem(documentId: String, name: String, series: String?) = ImportItem(
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
            name = name,
            kind = null,
            guess = guessType(rootName, null),
            folderTree = tree,
            folderDocumentId = documentId,
            seriesHint = series,
        )

        val children = folder.children(tree, rootId)
        if (children.any { !it.isFolder && imageNames.containsMatchIn(it.name) }) {
            // A folder of images is one book.
            items += folderItem(rootId, rootName, null)
            return
        }
        // Anything else is a series: each subfolder of images and each book file is one entry.
        children.forEach { child ->
            if (child.isFolder) {
                val images = folder.children(tree, child.documentId).any { !it.isFolder && imageNames.containsMatchIn(it.name) }
                if (images) items += folderItem(child.documentId, child.name, rootName) else skipped += child.name
            } else {
                addFile(DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId), child.name, rootName, items, skipped)
            }
        }
    }

    suspend fun run(items: List<ImportItem>, options: ImportOptions, onProgress: (ImportProgress) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            var duplicates = 0
            var originalsKept = 0
            val failed = ArrayList<String>()
            items.forEachIndexed { index, item ->
                onProgress(ImportProgress(index, items.size, item.name))
                try {
                    val type = options.types[item] ?: item.guess
                    val added = importOne(item, type, options)
                    if (added) {
                        imported++
                        if (options.removeOriginals && !removeOriginal(item)) originalsKept++
                    } else {
                        duplicates++
                    }
                } catch (error: Exception) {
                    failed += "${item.name}: ${error.message}"
                }
            }
            onProgress(ImportProgress(items.size, items.size, ""))
            ImportResult(imported, duplicates, failed, originalsKept)
        }

    private fun removeOriginal(item: ImportItem): Boolean = runCatching {
        DocumentsContract.deleteDocument(appContext.contentResolver, item.uri)
    }.getOrDefault(false)

    private suspend fun importOne(item: ImportItem, type: BookType, options: ImportOptions): Boolean {
        val hash = if (item.isFolder) {
            folder.hashFolder(item.folderTree!!, item.folderDocumentId!!)
        } else {
            folder.hashFile(item.uri)
        }
        if (repository.hasHash(hash)) return false

        val parent = folder.typeFolder(type)
        val copy = if (item.isFolder) {
            folder.copyFolder(item.folderTree!!, item.folderDocumentId!!, parent, item.name)
        } else {
            folder.copyFile(item.uri, parent, item.name)
        }

        try {
            val id = UUID.randomUUID().toString()
            val details = readDetails(item, type, copy)
            val coverPath = Covers.save(appContext, id, details.cover ?: Covers.placeholder(details.title, hash.hashCode()))
            val now = System.currentTimeMillis()
            val book = Book(
                id = id,
                hash = hash,
                type = type,
                format = item.kind?.name ?: FOLDER_FORMAT,
                title = details.title,
                author = details.author,
                seriesId = null,
                seriesNumber = details.number,
                documentUri = copy.toString(),
                isFolder = item.isFolder,
                pageCount = details.pageCount,
                coverPath = coverPath,
                customCoverPath = null,
                addedAt = now,
                updatedAt = now,
                lastReadAt = null,
                position = 0,
                locator = null,
                progress = 0f,
                inReadList = options.addToReadList,
                direction = type.direction,
            )
            val shelves = options.shelfId?.let { listOf(it) }
                ?: repository.state.value.shelves.filter { it.defaultType == type }.map { it.id }
            repository.addBook(book, details.series ?: item.seriesHint, shelves)
        } catch (error: Exception) {
            runCatching { folder.delete(copy) }
            throw error
        }
        return true
    }

    private suspend fun readDetails(item: ImportItem, type: BookType, copy: Uri): Details {
        val parsed = parseName(item.name, allowTrailingNumber = type != BookType.Book)
        fun fromName(pageCount: Int, cover: android.graphics.Bitmap?, title: String = parsed.title) =
            Details(title, "", parsed.series, parsed.number, pageCount, cover)

        return when (item.kind) {
            Kind.EPUB -> {
                val publication = openEpub(appContext, copy)
                try {
                    val metadata = publication.metadata
                    val series = metadata.belongsToSeries.firstOrNull()
                    Details(
                        title = metadata.title?.takeIf { it.isNotBlank() } ?: parsed.title,
                        author = metadata.authors.joinToString(", ") { it.name },
                        series = series?.name ?: parsed.series,
                        number = series?.position ?: parsed.number,
                        pageCount = 0,
                        cover = runCatching { publication.cover() }.getOrNull(),
                    )
                } finally {
                    publication.close()
                }
            }
            Kind.PDF -> PdfPageSource(appContext, copy, item.name).use { fromName(it.pageCount, firstPage(it)) }
            Kind.ZIP, Kind.RAR4, Kind.RAR5, Kind.SEVEN_ZIP ->
                ArchivePageSource(appContext, copy, item.name, item.kind ?: Kind.ZIP).use { source ->
                    val info = source.comicInfo()?.let(::parseComicInfo)
                    Details(
                        title = info?.title ?: parsed.title,
                        author = info?.writer.orEmpty(),
                        series = info?.series ?: parsed.series,
                        number = info?.number ?: parsed.number,
                        pageCount = source.pageCount,
                        cover = firstPage(source),
                    )
                }
            null -> {
                val documentId = DocumentsContract.getDocumentId(copy)
                FolderPageSource(appContext, folder.treeUri!!, item.name, documentId).use { source ->
                    fromName(source.pageCount, firstPage(source))
                }
            }
            else -> fromName(0, null)
        }
    }

    private fun firstPage(source: PageSource) =
        if (source.pageCount > 0) runCatching { source.render(0, COVER_WIDTH) }.getOrNull() else null

    companion object {
        const val FOLDER_FORMAT = "FOLDER"
    }
}
