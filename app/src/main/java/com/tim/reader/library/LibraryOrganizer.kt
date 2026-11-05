package com.tim.reader.library

import android.content.Context
import android.net.Uri
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps each book in its folder: Type/Book, or Type/Series/Book. */
class LibraryOrganizer(context: Context) {
    private val folder = LibraryFolder.get(context)
    private val repository = LibraryRepository.get(context)

    /** Moves these books to where they belong. Returns how many moved. */
    suspend fun place(bookIds: Collection<String>): Int = withContext(Dispatchers.IO) {
        val state = repository.state.value
        val ids = bookIds.toSet()
        val targets = HashMap<Pair<BookType, String?>, String>()
        val moved = HashMap<String, String>()
        val emptied = HashSet<String>()

        for (book in state.books.filter { it.id in ids }) {
            val series = state.seriesFor(book.seriesId)?.title
            val target = targets.getOrPut(book.type to series) { folder.bookFolder(book.type, series) }
            val document = Uri.parse(book.documentUri)
            val parent = folder.parentOf(document) ?: continue
            if (parent == target) continue
            folder.move(document, parent, target)?.let { newAddress ->
                moved[book.id] = newAddress.toString()
                emptied += parent
            }
        }
        repository.setDocumentUris(moved)
        emptied.forEach { folder.removeIfEmpty(it) }
        moved.size
    }
}
