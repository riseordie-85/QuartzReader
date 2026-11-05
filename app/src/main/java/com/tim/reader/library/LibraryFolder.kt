package com.tim.reader.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.edit
import com.tim.reader.data.BookType
import java.io.InputStream
import java.security.MessageDigest

/** One document inside a chosen folder. */
data class FolderChild(val documentId: String, val name: String, val isFolder: Boolean)

private const val BINARY = "application/octet-stream"

/** The one folder the user picks at first launch. The app organizes everything inside it. */
class LibraryFolder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("library", Context.MODE_PRIVATE)

    val treeUri: Uri?
        get() = preferences.getString(KEY_TREE, null)?.let(Uri::parse)

    val displayPath: String
        get() = treeUri?.let { DocumentsContract.getTreeDocumentId(it) }.orEmpty().replace(':', '/')

    /** True while Android still lets the app use the folder. */
    fun isReady(): Boolean {
        val tree = treeUri ?: return false
        return resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }

    fun choose(tree: Uri) {
        resolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.edit { putString(KEY_TREE, tree.toString()) }
    }

    fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(requireTree(), documentId)

    private fun requireTree() = treeUri ?: error("No library folder has been chosen")

    /** The folder a book of this type goes in. */
    fun typeFolder(type: BookType): String {
        val tree = requireTree()
        return findOrCreateFolder(tree, DocumentsContract.getTreeDocumentId(tree), type.folderName)
    }

    private fun findOrCreateFolder(tree: Uri, parentId: String, name: String): String {
        children(tree, parentId).firstOrNull { it.isFolder && it.name == name }?.let { return it.documentId }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
            ?: error("Android couldn't create the folder $name")
        return DocumentsContract.getDocumentId(created)
    }

    fun children(tree: Uri, parentId: String): List<FolderChild> {
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(listing, columns, null, null, null)?.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(FolderChild(rows.getString(0), rows.getString(1).orEmpty(), rows.getString(2) == Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty()
    }

    /** Copies one file into a library folder and returns the new document. */
    fun copyFile(source: Uri, parentId: String, name: String): Uri {
        val target = DocumentsContract.createDocument(resolver, documentUri(parentId), BINARY, safeFileName(name))
            ?: error("Android couldn't create $name")
        try {
            open(source).use { input ->
                (resolver.openOutputStream(target) ?: error("Android couldn't write $name")).use { input.copyTo(it, BUFFER) }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        }
        return target
    }

    /** Copies the images of a folder book into a new library folder. */
    fun copyFolder(sourceTree: Uri, sourceId: String, parentId: String, name: String): Uri {
        val target = DocumentsContract.createDocument(
            resolver,
            documentUri(parentId),
            Document.MIME_TYPE_DIR,
            safeFileName(name),
        ) ?: error("Android couldn't create the folder $name")
        try {
            val targetId = DocumentsContract.getDocumentId(target)
            for (child in children(sourceTree, sourceId)) {
                if (!child.isFolder) {
                    copyFile(DocumentsContract.buildDocumentUriUsingTree(sourceTree, child.documentId), targetId, child.name)
                }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        }
        return target
    }

    /**
     * Moves a book to the folder for its new type. Returns its new address, or null if Android
     * refuses, in which case the book stays where it is and keeps working.
     */
    fun moveBetweenTypes(document: Uri, from: BookType, to: BookType): Uri? = runCatching {
        val tree = requireTree()
        DocumentsContract.moveDocument(
            resolver,
            document,
            DocumentsContract.buildDocumentUriUsingTree(tree, typeFolder(from)),
            DocumentsContract.buildDocumentUriUsingTree(tree, typeFolder(to)),
        )
    }.getOrNull()

    fun delete(document: Uri) {
        DocumentsContract.deleteDocument(resolver, document)
    }

    /** Fingerprints a file, which is how the same book is recognized later, even on another device. */
    fun hashFile(source: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open(source).use { digest.feed(it) }
        return digest.digest().toHex()
    }

    /** Fingerprints a folder book from its file names and contents. */
    fun hashFolder(tree: Uri, documentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        children(tree, documentId).filterNot { it.isFolder }.sortedBy { it.name }.forEach { child ->
            digest.update(child.name.toByteArray())
            open(DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId)).use { digest.feed(it) }
        }
        return digest.digest().toHex()
    }

    private fun open(source: Uri): InputStream =
        resolver.openInputStream(source) ?: error("Android couldn't open this file")

    private fun MessageDigest.feed(input: InputStream) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_TREE = "tree"
        private const val BUFFER = 256 * 1024

        @Volatile
        private var instance: LibraryFolder? = null

        fun get(context: Context): LibraryFolder =
            instance ?: synchronized(this) {
                instance ?: LibraryFolder(context).also { instance = it }
            }
    }
}
