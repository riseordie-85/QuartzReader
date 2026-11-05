package com.tim.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tim.reader.data.Book
import com.tim.reader.engine.ArchivePageSource
import com.tim.reader.engine.FolderPageSource
import com.tim.reader.engine.Kind
import com.tim.reader.engine.PageSource
import com.tim.reader.engine.PdfPageSource
import com.tim.reader.engine.describeMobi
import com.tim.reader.engine.sniff
import com.tim.reader.library.Importer
import com.tim.reader.library.LibraryFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

sealed interface Opened {
    class Images(val source: PageSource) : Opened
    data object Epub : Opened
    class Message(val text: String) : Opened
}

/** Hands the open EPUB to EpubActivity. */
object OpenEpub {
    var publication: Publication? = null
    var bookId: String? = null
    var locator: String? = null
}

/** For work that should finish even after the screen that started it is gone. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Opens a library book in the fixed-page reader. */
fun openBookSource(context: Context, book: Book): PageSource {
    val uri = Uri.parse(book.documentUri)
    return when (book.format) {
        Importer.FOLDER_FORMAT -> {
            val tree = LibraryFolder.get(context).treeUri ?: error("No library folder has been chosen")
            FolderPageSource(context, tree, book.title, DocumentsContract.getDocumentId(uri))
        }
        Kind.PDF.name -> PdfPageSource(context, uri, book.title)
        else -> ArchivePageSource(context, uri, book.title, Kind.valueOf(book.format))
    }
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
        if (rows.moveToFirst()) rows.getString(0)?.let { return it }
    }
    return uri.lastPathSegment.orEmpty()
}

suspend fun openFile(context: Context, uri: Uri): Opened = withContext(Dispatchers.IO) {
    val name = runCatching { displayName(context, uri) }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    try {
        val head = ByteArray(128)
        val headLength = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(head, 0, head.size) }
        val kind = sniff(head.copyOf(headLength), name)
        ResultsLog.add("--- $name: detected ${kind.label}")
        when (kind) {
            Kind.ZIP, Kind.RAR4, Kind.RAR5, Kind.SEVEN_ZIP -> {
                val source = timed("Index archive") { ArchivePageSource(context, uri, name, kind) }
                ResultsLog.add("${source.pageCount} pages in ${source.chapterCount} chapter folders")
                if (source.duplicateNested.isNotEmpty()) {
                    ResultsLog.add("Skipped ${source.duplicateNested.size} nested archives that repeat their folder")
                }
                if (source.unreadNested.isNotEmpty()) {
                    ResultsLog.add("Not read yet: ${source.unreadNested.size} nested archives with their own pages")
                }
                if (source.pageCount == 0) {
                    source.close()
                    Opened.Message("No readable pages in $name")
                } else {
                    Opened.Images(source)
                }
            }
            Kind.PDF -> {
                val source = timed("Open PDF") { PdfPageSource(context, uri, name) }
                ResultsLog.add("${source.pageCount} PDF pages")
                Opened.Images(source)
            }
            Kind.EPUB -> {
                OpenEpub.publication = timed("Open EPUB") { openEpub(context, uri) }
                Opened.Epub
            }
            Kind.MOBI -> {
                val detail = context.contentResolver.openInputStream(uri)!!.use { describeMobi(it.readBytes()) }
                ResultsLog.add(detail)
                Opened.Message("$detail\n\nConversion to EPUB arrives in Phase 7. This test only reads the header.")
            }
            Kind.UNKNOWN -> {
                ResultsLog.add("Unknown format")
                Opened.Message("Quartz Reader doesn't recognize $name")
            }
        }
    } catch (error: Throwable) {
        ResultsLog.add("FAILED opening $name: ${error::class.simpleName}: ${error.message}")
        Opened.Message("Couldn't open $name\n${error::class.simpleName}: ${error.message}")
    }
}

suspend fun openFolder(context: Context, tree: Uri): Opened = withContext(Dispatchers.IO) {
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    val name = documentId.substringAfterLast('/').substringAfterLast(':')
    try {
        ResultsLog.add("--- Folder $name")
        val source = timed("Index folder") { FolderPageSource(context, tree, name) }
        ResultsLog.add("${source.pageCount} pages")
        if (source.pageCount == 0) {
            source.close()
            Opened.Message("No images in $name")
        } else {
            Opened.Images(source)
        }
    } catch (error: Throwable) {
        ResultsLog.add("FAILED opening folder $name: ${error::class.simpleName}: ${error.message}")
        Opened.Message("Couldn't open $name\n${error.message}")
    }
}

suspend fun openEpub(context: Context, uri: Uri): Publication {
    val client = DefaultHttpClient()
    val retriever = AssetRetriever(context.contentResolver, client)
    val parser = DefaultPublicationParser(context, client, retriever, null)
    val url = uri.toAbsoluteUrl() ?: error("Readium can't read this document address")
    val asset = retriever.retrieve(url).fold({ it }, { failure -> error(failure.message) })
    return PublicationOpener(parser)
        .open(asset, allowUserInteraction = false)
        .fold({ it }, { failure -> error(failure.message) })
}
