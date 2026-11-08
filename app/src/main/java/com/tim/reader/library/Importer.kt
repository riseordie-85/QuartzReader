package com.tim.reader.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tim.reader.data.Book
import com.tim.reader.data.BookInfo
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.ReadDirection
import com.tim.reader.engine.ArchivePageSource
import com.tim.reader.engine.FolderPageSource
import com.tim.reader.engine.Kind
import com.tim.reader.engine.PageSource
import com.tim.reader.engine.PdfPageSource
import com.tim.reader.engine.sniff
import com.tim.reader.openEpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.cover
import java.util.UUID

private val imageNames = Regex("""\.(jpe?g|png|webp|gif|bmp|avif|heic|jp2|j2k|jpx|jpf)$""", RegexOption.IGNORE_CASE)
private val bookNames = Regex("""\.(epub|pdf|cbz|cbr|cb7|zip|rar|7z|mobi|azw3?|prc)$""", RegexOption.IGNORE_CASE)
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
    /** Already in the library folder, so it's added where it is instead of copied. */
    val inPlace: Boolean = false,
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
    val rightToLeft: Boolean = false,
    val info: BookInfo = BookInfo(),
)

/** EPUB descriptions are often HTML. */
private fun plainText(text: String) = plainDescription(text)

class Importer(context: Context) {
    private val appContext = context.applicationContext
    private val folder = LibraryFolder.get(appContext)
    private val repository = LibraryRepository.get(appContext)
    private val organizer = LibraryOrganizer(appContext)

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
        manhwaNames.containsMatchIn(name) -> BookType.Manhwa
        mangaNames.containsMatchIn(name) -> BookType.Manga
        kind == null -> BookType.Manhwa
        kind == Kind.EPUB || kind == Kind.MOBI || kind == Kind.PDF -> BookType.Book
        else -> BookType.Comic
    }

    /** Works out what each picked file or folder is. Returns what can be imported, and what can't. */
    suspend fun scan(files: List<Uri>, folders: List<Uri>): Pair<List<ImportItem>, List<String>> =
        withContext(Dispatchers.IO) {
            val items = ArrayList<ImportItem>()
            val skipped = ArrayList<String>()
            files.forEach { uri ->
                val name = displayName(uri)
                addFile(uri, name, null, name, items, skipped)
            }
            folders.forEach { tree -> scanFolder(tree, items, skipped) }
            items to skipped
        }

    private fun addFile(
        uri: Uri,
        name: String,
        series: String?,
        path: String,
        items: MutableList<ImportItem>,
        skipped: MutableList<String>,
    ) {
        val kind = runCatching { readKind(uri, name) }.getOrDefault(Kind.UNKNOWN)
        if (kind == Kind.UNKNOWN) {
            skipped += name
        } else {
            items += ImportItem(uri, name, kind, guessType(path, kind), seriesHint = series)
        }
    }

    /** True for names like "Vol. 3", "Chapter 12", and "007", which mark one part of a series. */
    private fun isNumbered(name: String) =
        parseName(name).number != null || name.trim().toDoubleOrNull() != null

    /** The series a folder stands for, without tags like (2021) or [Digital]. */
    private fun seriesName(folderName: String) =
        parseName(folderName, allowTrailingNumber = false).let { it.series ?: it.title }

    /**
     * Walks everything under a picked folder. A folder of nothing but images is one book. Each book
     * file is one book. Numbered entries form a series named after their folder, while unnumbered
     * ones stand alone, so a folder of unrelated titles stays unrelated.
     */
    private fun scanFolder(tree: Uri, items: MutableList<ImportItem>, skipped: MutableList<String>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':')
        val libraryRoot = folder.treeUri?.let { DocumentsContract.getTreeDocumentId(it) }

        fun walk(documentId: String, name: String, parent: String?, path: String, depth: Int) {
            val children = folder.children(tree, documentId).filterNot { it.name.startsWith(".") }
            val files = children.filterNot { it.isFolder }
            val folders = children.filter { it.isFolder && it.documentId != libraryRoot }
            val pages = files.filter { imageNames.containsMatchIn(it.name) }
            val others = files - pages.toSet()

            if (pages.isNotEmpty() && folders.isEmpty() && others.none { bookNames.containsMatchIn(it.name) }) {
                items += ImportItem(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                    name = name,
                    kind = null,
                    guess = guessType(path, null),
                    folderTree = tree,
                    folderDocumentId = documentId,
                    seriesHint = parent?.takeIf { isNumbered(name) }?.let(::seriesName),
                )
                return
            }
            others.forEach { file ->
                val hint = if (isNumbered(file.name)) seriesName(name) else null
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
                addFile(uri, file.name, hint, "$path ${file.name}", items, skipped)
            }
            folders.forEach { child ->
                if (depth < MAX_DEPTH) {
                    walk(child.documentId, child.name, name, "$path ${child.name}", depth + 1)
                } else {
                    skipped += child.name
                }
            }
        }

        walk(rootId, rootName, null, rootName, 0)
    }

    /**
     * Finds books already in the library folder that the library doesn't know, such as after a
     * reinstall. The folder layout is the app's own: Type/Book or Type/Series/Book, where a folder
     * of images is one book. Each book's type comes from its top folder.
     */
    suspend fun scanLibrary(): Pair<List<ImportItem>, List<String>> = withContext(Dispatchers.IO) {
        val items = ArrayList<ImportItem>()
        val skipped = ArrayList<String>()
        val tree = folder.treeUri ?: return@withContext items to skipped
        val known = repository.state.value.books.mapNotNull { book ->
            runCatching { DocumentsContract.getDocumentId(Uri.parse(book.documentUri)) }.getOrNull()
        }.toSet()
        val top = folder.children(tree, DocumentsContract.getTreeDocumentId(tree))

        for (type in BookType.entries) {
            val typeFolder = top.firstOrNull { it.isFolder && it.name.equals(type.folderName, ignoreCase = true) } ?: continue

            // parentSeries is the series folder above this one, if any.
            fun walk(documentId: String, name: String, parentSeries: String?, depth: Int) {
                val children = folder.children(tree, documentId).filterNot { it.name.startsWith(".") }
                val files = children.filterNot { it.isFolder }
                val folders = children.filter { it.isFolder }
                val pages = files.filter { imageNames.containsMatchIn(it.name) }
                val others = files - pages.toSet()

                if (depth > 0 && pages.isNotEmpty() && folders.isEmpty() && others.none { bookNames.containsMatchIn(it.name) }) {
                    if (documentId !in known) {
                        items += ImportItem(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                            name = name,
                            kind = null,
                            guess = type,
                            folderTree = tree,
                            folderDocumentId = documentId,
                            seriesHint = parentSeries,
                            inPlace = true,
                        )
                    }
                    return
                }
                // Files in a type folder stand alone. Anything deeper belongs to the series folder it's in.
                val series = parentSeries ?: if (depth > 0) seriesName(name) else null
                others.forEach { file ->
                    if (file.documentId in known) return@forEach
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
                    val kind = runCatching { readKind(uri, file.name) }.getOrDefault(Kind.UNKNOWN)
                    if (kind == Kind.UNKNOWN) {
                        skipped += file.name
                    } else {
                        items += ImportItem(uri, file.name, kind, type, seriesHint = series, inPlace = true)
                    }
                }
                folders.forEach { child ->
                    if (depth < MAX_DEPTH) walk(child.documentId, child.name, series, depth + 1) else skipped += child.name
                }
            }

            walk(typeFolder.documentId, typeFolder.name, null, 0)
        }
        items to skipped
    }

    suspend fun run(items: List<ImportItem>, options: ImportOptions, onProgress: (ImportProgress) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            var duplicates = 0
            var originalsKept = 0
            val failed = ArrayList<String>()
            items.forEachIndexed { index, item ->
                ensureActive()
                onProgress(ImportProgress(index, items.size, item.name))
                try {
                    val type = options.types[item] ?: item.guess
                    val added = importOne(item, type, options)
                    if (added) {
                        imported++
                        if (options.removeOriginals && !item.inPlace && !removeOriginal(item)) originalsKept++
                    } else {
                        duplicates++
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failed += "${item.name}: ${error.message}"
                }
            }
            onProgress(ImportProgress(items.size, items.size, ""))
            ImportResult(imported, duplicates, failed, originalsKept)
        }

    /** Deletes only what was copied. A folder goes only once nothing is left in it. */
    private fun removeOriginal(item: ImportItem): Boolean = runCatching {
        val resolver = appContext.contentResolver
        val tree = item.folderTree ?: return@runCatching DocumentsContract.deleteDocument(resolver, item.uri)
        val children = folder.children(tree, item.folderDocumentId!!)
        val filesGone = children.filterNot { it.isFolder }.all {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.documentId))
        }
        filesGone && children.none { it.isFolder } && DocumentsContract.deleteDocument(resolver, item.uri)
    }.getOrDefault(false)

    private suspend fun importOne(item: ImportItem, type: BookType, options: ImportOptions): Boolean {
        val hash = if (item.isFolder) {
            folder.hashFolder(item.folderTree!!, item.folderDocumentId!!)
        } else {
            folder.hashFile(item.uri)
        }
        val existing = repository.state.value.books.firstOrNull { it.hash == hash }
        if (existing != null) {
            if (item.inPlace) return false
            // The same file arriving with its series tells us where a book imported alone belongs.
            val parsed = parseName(item.name, allowTrailingNumber = type != BookType.Book)
            val hint = item.seriesHint?.takeIf { item.isFolder || type != BookType.Book }
            val series = parsed.series ?: hint
            if (existing.seriesId == null && series != null) {
                repository.assignSeries(existing.id, series, parsed.number ?: existing.seriesNumber)
                organizer.place(listOf(existing.id))
            }
            return false
        }

        val copy = when {
            item.inPlace -> item.uri
            item.isFolder -> folder.copyFolder(item.folderTree!!, item.folderDocumentId!!, folder.typeFolder(type), item.name)
            else -> folder.copyFile(item.uri, folder.typeFolder(type), item.name)
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
                direction = if (details.rightToLeft) ReadDirection.RightToLeft else type.direction,
                info = details.info,
            )
            val shelves = options.shelfId?.let { listOf(it) }
                ?: repository.state.value.shelves.filter { it.defaultType == type }.map { it.id }
            // Loose novels in a folder aren't assumed to be a series, but volumes of comics are.
            // In the library folder, a series folder is a series whatever the type.
            val hint = item.seriesHint?.takeIf { item.inPlace || item.isFolder || type != BookType.Book }
            repository.addBook(book, details.series ?: hint, shelves)
            // The book is in the library now, so a failed move mustn't undo the import.
            // Books found in the library folder stay exactly where they are.
            if (!item.inPlace) runCatching { organizer.place(listOf(id)) }
        } catch (error: Exception) {
            if (!item.inPlace) runCatching { folder.delete(copy) }
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
                        info = BookInfo(
                            description = metadata.description?.let { plainText(it) }.orEmpty(),
                            genres = metadata.subjects.map { it.name.trim() }.filter { it.isNotEmpty() },
                            publisher = metadata.publishers.firstOrNull()?.name.orEmpty(),
                            year = metadata.published?.toString()?.take(4)?.takeIf { year -> year.all { it.isDigit() } }.orEmpty(),
                            source = "File",
                        ),
                    )
                } finally {
                    publication.close()
                }
            }
            Kind.PDF -> PdfPageSource(appContext, copy, item.name).use { fromName(it.pageCount, firstPage(it)) }
            Kind.ZIP, Kind.RAR4, Kind.RAR5, Kind.SEVEN_ZIP ->
                ArchivePageSource(appContext, copy, item.name, item.kind ?: Kind.ZIP).use { source ->
                    require(source.pageCount > 0) { "No readable pages" }
                    val info = source.comicInfo()?.let(::parseComicInfo)
                    Details(
                        title = info?.title ?: parsed.title,
                        author = info?.writer.orEmpty(),
                        series = info?.series ?: parsed.series,
                        number = info?.number ?: parsed.number,
                        pageCount = source.pageCount,
                        cover = firstPage(source),
                        rightToLeft = info?.rightToLeft == true,
                        info = BookInfo(
                            description = info?.summary.orEmpty(),
                            genres = info?.genres.orEmpty(),
                            publisher = info?.publisher.orEmpty(),
                            year = info?.year.orEmpty(),
                            source = if (info != null) "File" else "",
                        ),
                    )
                }
            null -> {
                val documentId = DocumentsContract.getDocumentId(copy)
                FolderPageSource(appContext, folder.treeUri!!, item.name, documentId).use { source ->
                    require(source.pageCount > 0) { "No readable pages" }
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
        private const val MAX_DEPTH = 6
    }
}
