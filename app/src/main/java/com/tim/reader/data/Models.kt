package com.tim.reader.data

import com.tim.reader.engine.NaturalOrder
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
    VerticalPaged("Top to bottom, by page"),
}

/** How a page fits the screen. */
enum class FitMode(val label: String) {
    Screen("Whole page"),
    Width("Fit width"),
    Height("Fit height"),
}

enum class PageAnimation(val label: String) {
    Slide("Slide"),
    Curl("Page curl"),
    Fade("Fade"),
    None("None"),
}

/** Reader settings at one level. A null value falls back to the level above it. */
data class ReaderPrefs(
    val fit: FitMode? = null,
    val animation: PageAnimation? = null,
    val background: Long? = null,
)

/** The settings a book actually reads with, after Book, then Type, then Global. */
data class ReaderLook(
    val fit: FitMode = FitMode.Screen,
    val animation: PageAnimation = PageAnimation.Slide,
    val background: Long = 0xFF000000,
)

data class Bookmark(val id: String, val bookId: String, val page: Int, val label: String, val createdAt: Long)

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
    val readerPrefs: ReaderPrefs = ReaderPrefs(),
    val spreadShift: Boolean = false,
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
    val globalPrefs: ReaderPrefs = ReaderPrefs(),
    val typePrefs: Map<BookType, ReaderPrefs> = emptyMap(),
    val bookmarks: Map<String, List<Bookmark>> = emptyMap(),
) {
    fun readerLook(book: Book): ReaderLook {
        val type = typePrefs[book.type] ?: ReaderPrefs()
        val defaults = ReaderLook()
        return ReaderLook(
            fit = book.readerPrefs.fit ?: type.fit ?: globalPrefs.fit ?: defaults.fit,
            animation = book.readerPrefs.animation ?: type.animation ?: globalPrefs.animation ?: defaults.animation,
            background = book.readerPrefs.background ?: type.background ?: globalPrefs.background ?: defaults.background,
        )
    }

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

/** Changes to every volume of a series. A null value leaves that field as it is on each volume. */
class SeriesEdit(
    val title: String,
    val author: String?,
    val type: BookType?,
    val direction: ReadDirection?,
    val addShelves: Set<String>,
    val removeShelves: Set<String>,
)

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

/** Volume order: by number, then by title with Vol. 2 before Vol. 10. */
val volumeOrder: Comparator<Book> =
    compareBy<Book> { it.seriesNumber ?: Double.MAX_VALUE }.thenBy(NaturalOrder) { it.title }

fun sortBooks(books: List<Book>, sort: SortOrder, state: LibraryState): List<Book> {
    fun seriesTitle(book: Book) = state.seriesFor(book.seriesId)?.title ?: book.title
    return when (sort) {
        SortOrder.Title -> books.sortedWith(compareBy(NaturalOrder) { it.title })
        SortOrder.Author -> books.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
        SortOrder.Series -> books.sortedWith(compareBy<Book> { seriesTitle(it).lowercase() }.then(volumeOrder))
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
            items += ShelfItem.Stack(series, volumes.sortedWith(volumeOrder))
        }
    }
    return items
}

/** The volume to open from a series: the first one not finished. */
fun nextVolume(volumes: List<Book>): Book =
    volumes.firstOrNull { it.progress < 0.99f } ?: volumes.first()
