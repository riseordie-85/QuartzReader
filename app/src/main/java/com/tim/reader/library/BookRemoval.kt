package com.tim.reader.library

import android.content.Context
import android.net.Uri
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deletes books, their copies in the library folder, and their covers. */
suspend fun deleteBooks(context: Context, books: List<Book>) {
    withContext(Dispatchers.IO) {
        val folder = LibraryFolder.get(context)
        val emptied = HashSet<String>()
        books.forEach { book ->
            val document = Uri.parse(book.documentUri)
            folder.parentOf(document)?.let { emptied += it }
            runCatching { folder.delete(document) }
            Covers.delete(context, book.id)
            Covers.deleteCustom(book.customCoverPath)
        }
        emptied.forEach { runCatching { folder.removeIfEmpty(it) } }
    }
    val repository = LibraryRepository.get(context)
    books.forEach { repository.deleteBook(it.id) }
}
