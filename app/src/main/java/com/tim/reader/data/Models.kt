package com.tim.reader.data

import com.tim.reader.theme.ShelfTheme

enum class BookType(val label: String, val folderName: String, val direction: ReadDirection) {
    Book("Book", "Books", ReadDirection.LeftToRight),
    Comic("Comic", "Comics", ReadDirection.LeftToRight),
    Manga("Manga", "Manga", ReadDirection.RightToLeft),
    Manhwa("Manhwa", "Manhwa", ReadDirection.Vertical),
}

enum class ReadDirection(val label: String) {
    LeftToRight("Left to right"),
    RightToLeft("Right to left"),
    Vertical("Vertical"),
}

/** The three views the grid icon cycles through. */
enum class ViewMode { Shelf, Grid, List }

enum class SortOrder(val label: String) {
    Title("Title"),
    Author("Author"),
    Series("Series"),
    RecentlyAdded("Recently added"),
    RecentlyRead("Recently read"),
    Progress("Progress"),
}

data class Book(
    val id: String,
    val hash: String,
    val type: BookType,
    val format: String,
    val title: String,
    val author: String,
    val seriesId: String?,
    val seriesNumber: Double?,
    val documentUri: String,
    val isFolder: Boolean,
    val pageCount: Int,
    val coverPath: String?,
    val customCoverPath: String?,
    val addedAt: Long,
    val updatedAt: Long,
    val lastReadAt: Long?,
    val position: Int,
    val locator: String?,
    val progress: Float,
    val inReadList: Boolean,
    val direction: ReadDirection,
) {
    val displayCover get() = customCoverPath ?: coverPath
}

data class Series(val id: String, val title: String, val updatedAt: Long)

data class Shelf(
    val id: String,
    val name: String,
    val order: Int,
    val theme: ShelfTheme,
    val viewMode: ViewMode,
    val sort: SortOrder,
    val groupSeries: Boolean,
    val defaultType: BookType?,
    val updatedAt: Long,
)

/** Everything on screen comes from this one snapshot of the database. */
data class LibraryState(
    val books: List<Book> = emptyList(),
    val series: List<Series> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val shelfBooks: Map<String, Set<String>> = emptyMap(),
    val homeTheme: ShelfTheme? = null,
) {
    private val booksById by lazy { books.associateBy { it.id } }
    private val seriesById by lazy { series.associateBy { it.id } }

    fun book(id: String) = booksById[id]
    fun seriesFor(id: String?) = id?.let { seriesById[it] }
    fun shelf(id: String) = shelves.firstOrNull { it.id == id }
    fun booksOn(shelfId: String): List<Book> {
        val ids = shelfBooks[shelfId] ?: return emptyList()
        return books.filter { it.id in ids }
    }
    val recentlyRead get() = books.filter { it.lastReadAt != null }.sortedByDescending { it.lastReadAt }
    val readList get() = books.filter { it.inReadList }.sortedByDescending { it.addedAt }
}

/** What a shelf shows: one book, or a series as a single stack. */
sealed interface ShelfItem {
    val key: String

    data class Single(val book: Book) : ShelfItem {
        override val key get() = book.id
    }

    data class Stack(val series: Series, val volumes: List<Book>) : ShelfItem {
        override val key get() = "series:${series.id}"
    }
}

fun sortBooks(books: List<Book>, sort: SortOrder, state: LibraryState): List<Book> {
    fun seriesTitle(book: Book) = state.seriesFor(book.seriesId)?.title ?: book.title
    return when (sort) {
        SortOrder.Title -> books.sortedBy { it.title.lowercase() }
        SortOrder.Author -> books.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
        SortOrder.Series -> books.sortedWith(
            compareBy({ seriesTitle(it).lowercase() }, { it.seriesNumber ?: Double.MAX_VALUE }, { it.title.lowercase() }),
        )
        SortOrder.RecentlyAdded -> books.sortedByDescending { it.addedAt }
        SortOrder.RecentlyRead -> books.sortedByDescending { it.lastReadAt ?: 0L }
        SortOrder.Progress -> books.sortedByDescending { it.progress }
    }
}

/** Groups volumes of a series together. A series with one book on this shelf stays a plain book. */
fun shelfItems(books: List<Book>, sort: SortOrder, group: Boolean, state: LibraryState): List<ShelfItem> {
    val sorted = sortBooks(books, sort, state)
    if (!group) return sorted.map { ShelfItem.Single(it) }
    val bySeries = sorted.filter { it.seriesId != null }.groupBy { it.seriesId!! }
    val placed = HashSet<String>()
    val items = ArrayList<ShelfItem>()
    for (book in sorted) {
        val seriesId = book.seriesId
        val volumes = seriesId?.let { bySeries[it] }
        val series = state.seriesFor(seriesId)
        if (seriesId == null || volumes == null || volumes.size < 2 || series == null) {
            items += ShelfItem.Single(book)
        } else if (placed.add(seriesId)) {
            items += ShelfItem.Stack(series, volumes.sortedWith(compareBy({ it.seriesNumber ?: Double.MAX_VALUE }, { it.title })))
        }
    }
    return items
}

/** The volume to open from a series: the first one not finished. */
fun nextVolume(volumes: List<Book>): Book =
    volumes.firstOrNull { it.progress < 0.99f } ?: volumes.first()
