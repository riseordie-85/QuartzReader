package com.tim.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tim.reader.library.seriesMatchKey
import com.tim.reader.theme.Presets
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

private class ReaderDatabase(context: Context) : SQLiteOpenHelper(context, "library.db", null, Schema.VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        Schema.CREATE_ALL.forEach { db.execSQL(it) }
        Schema.UPGRADE_TO_2.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            Schema.UPGRADE_TO_2.forEach { db.execSQL(it) }
            separateSeriesByType(db)
        }
    }

    /** Series used to be shared by every book with the same name, whatever its type. */
    private fun separateSeriesByType(db: SQLiteDatabase) {
        class Row(val id: String, val title: String, val key: String, val type: String)

        val time = now()
        val rows = db.rawQuery(Schema.SELECT_SERIES_TYPES, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Row(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
                }
            }
        }
        val kept = HashSet<String>()
        for (row in rows) {
            val key = "${row.type}:${row.key}"
            if (kept.add(row.id)) {
                val values = ContentValues().apply {
                    put("match_key", key)
                    put("updated_at", time)
                }
                db.update("series", values, "id = ?", arrayOf(row.id))
            } else {
                val id = UUID.randomUUID().toString()
                val series = ContentValues().apply {
                    put("id", id)
                    put("title", row.title)
                    put("match_key", key)
                    put("updated_at", time)
                }
                db.insertOrThrow("series", null, series)
                val books = ContentValues().apply {
                    put("series_id", id)
                    put("updated_at", time)
                }
                db.update("books", books, "series_id = ? AND type = ? AND deleted_at IS NULL", arrayOf(row.id, row.type))
            }
        }
        db.execSQL(Schema.DELETE_EMPTY_SERIES, arrayOf<Any>(time, time))
    }
}

private fun now() = System.currentTimeMillis()

class LibraryRepository private constructor(context: Context) {
    private val helper = ReaderDatabase(context.applicationContext)
    private val mutableState = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()

    private val db get() = helper.writableDatabase

    /** Every database job runs one at a time, in order, so a reload can't undo a newer save. */
    private val serial = Dispatchers.IO.limitedParallelism(1)

    /** Loads the library. Called once at launch. */
    suspend fun load() = withContext(serial) {
        seedShelves()
        reload()
    }

    private fun reload() {
        val books = db.rawQuery(Schema.SELECT_BOOKS, null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.toBook()) }
        }
        val series = db.rawQuery(Schema.SELECT_SERIES, null).use { rows ->
            buildList { while (rows.moveToNext()) add(Series(rows.getString(0), rows.getString(1), rows.getLong(2))) }
        }
        val shelves = db.rawQuery(Schema.SELECT_SHELVES, null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.toShelf()) }
        }
        val membership = HashMap<String, MutableSet<String>>()
        db.rawQuery(Schema.SELECT_SHELF_BOOKS, null).use { rows ->
            while (rows.moveToNext()) {
                membership.getOrPut(rows.getString(0)) { HashSet() }.add(rows.getString(1))
            }
        }
        val home = setting(KEY_HOME_THEME)?.let { ThemeJson.read(it) } ?: Presets.silverGloss
        val bookmarks = db.rawQuery(Schema.SELECT_BOOKMARKS, null).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(Bookmark(rows.getString(0), rows.getString(1), rows.getInt(2), rows.getString(3), rows.getLong(4)))
                }
            }
        }
        mutableState.value = LibraryState(
            books = books,
            series = series,
            shelves = shelves,
            shelfBooks = membership,
            homeTheme = home,
            globalPrefs = readPrefs(setting(KEY_PREFS_GLOBAL)),
            typePrefs = BookType.entries.associateWith { readPrefs(setting(KEY_PREFS_TYPE + it.name)) },
            colorSets = readColorSets(setting(KEY_COLOR_SETS)),
            bookmarks = bookmarks.groupBy { it.bookId },
        )
    }

    private fun Cursor.toBook() = Book(
        id = getString(0),
        hash = getString(1),
        type = enumOr(getString(2), BookType.Book),
        format = getString(3),
        title = getString(4),
        author = getString(5),
        seriesId = getString(6),
        seriesNumber = if (isNull(7)) null else getDouble(7),
        documentUri = getString(8),
        isFolder = getInt(9) != 0,
        pageCount = getInt(10),
        coverPath = getString(11),
        customCoverPath = getString(12),
        addedAt = getLong(13),
        updatedAt = getLong(14),
        lastReadAt = if (isNull(15)) null else getLong(15),
        position = getInt(16),
        locator = getString(17),
        progress = getFloat(18),
        inReadList = getInt(19) != 0,
        direction = enumOr(getString(20), ReadDirection.LeftToRight),
        readerPrefs = readPrefs(getString(21)),
        spreadShift = getInt(22) != 0,
    )

    private fun readPrefs(json: String?): ReaderPrefs = try {
        val root = JSONObject(json ?: "{}")
        ReaderPrefs(
            fit = root.optString("fit").takeIf { it.isNotEmpty() }?.let { enumOr(it, FitMode.Screen) },
            animation = root.optString("animation").takeIf { it.isNotEmpty() }?.let { enumOr(it, PageAnimation.Slide) },
            background = root.longOrNull("background"),
            pageBackground = root.longOrNull("pageBackground"),
            textColor = root.longOrNull("textColor"),
            fontFamily = root.optString("fontFamily").takeIf { it.isNotEmpty() },
            fontSize = root.doubleOrNull("fontSize"),
            lineHeight = root.doubleOrNull("lineHeight"),
            paragraphSpacing = root.doubleOrNull("paragraphSpacing"),
            pageMargins = root.doubleOrNull("pageMargins"),
            alignment = root.optString("alignment").takeIf { it.isNotEmpty() }?.let { enumOr(it, TextAlignment.Start) },
            scrolling = if (root.has("scrolling")) root.getBoolean("scrolling") else null,
            bookLayout = if (root.has("bookLayout")) root.getBoolean("bookLayout") else null,
        )
    } catch (_: JSONException) {
        ReaderPrefs()
    }

    private fun JSONObject.longOrNull(key: String) = if (has(key)) getLong(key) else null

    private fun JSONObject.doubleOrNull(key: String) = if (has(key)) getDouble(key) else null

    private fun writePrefs(prefs: ReaderPrefs): String = JSONObject().apply {
        prefs.fit?.let { put("fit", it.name) }
        prefs.animation?.let { put("animation", it.name) }
        prefs.background?.let { put("background", it) }
        prefs.pageBackground?.let { put("pageBackground", it) }
        prefs.textColor?.let { put("textColor", it) }
        prefs.fontFamily?.let { put("fontFamily", it) }
        prefs.fontSize?.let { put("fontSize", it) }
        prefs.lineHeight?.let { put("lineHeight", it) }
        prefs.paragraphSpacing?.let { put("paragraphSpacing", it) }
        prefs.pageMargins?.let { put("pageMargins", it) }
        prefs.alignment?.let { put("alignment", it.name) }
        prefs.scrolling?.let { put("scrolling", it) }
        prefs.bookLayout?.let { put("bookLayout", it) }
    }.toString()

    private fun readColorSets(json: String?): List<ColorSet> = try {
        val rows = JSONArray(json ?: "[]")
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            ColorSet(row.getLong("background"), row.getLong("text"))
        }
    } catch (_: JSONException) {
        emptyList()
    }

    private fun Cursor.toShelf() = Shelf(
        id = getString(0),
        name = getString(1),
        order = getInt(2),
        theme = ThemeJson.read(getString(3)),
        viewMode = enumOr(getString(4), ViewMode.Shelf),
        sort = enumOr(getString(5), SortOrder.Title),
        groupSeries = getInt(6) != 0,
        defaultType = if (isNull(7)) null else enumOr(getString(7), BookType.Book),
        updatedAt = getLong(8),
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun setting(key: String): String? = db.rawQuery(Schema.SELECT_SETTING, arrayOf(key)).use { rows ->
        if (rows.moveToFirst()) rows.getString(0) else null
    }

    private fun saveSetting(key: String, value: String) {
        db.execSQL(Schema.UPSERT_SETTING, arrayOf<Any>(key, value, now()))
    }

    private fun seedShelves() {
        if (setting(KEY_SEEDED) != null) return
        val seeds = listOf(
            Triple("Books", Presets.wood, BookType.Book),
            Triple("Comics", Presets.brushedMetal, BookType.Comic),
            Triple("Manga", Presets.speedLines, BookType.Manga),
            Triple("Manhwa", Presets.burst, BookType.Manhwa),
        )
        db.beginTransaction()
        try {
            seeds.forEachIndexed { index, (name, theme, type) -> insertShelf(name, index, theme, type) }
            saveSetting(KEY_SEEDED, "1")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertShelf(name: String, order: Int, theme: ShelfTheme, type: BookType?): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("sort_position", order)
            put("theme", ThemeJson.write(theme))
            put("view_mode", ViewMode.Shelf.name)
            put("sort_order", SortOrder.Title.name)
            put("group_series", 1)
            put("default_type", type?.name)
            put("updated_at", now())
        }
        db.insertOrThrow("shelves", null, values)
        return id
    }

    private suspend fun write(block: () -> Unit) = withContext(serial) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reload()
    }

    private fun updateShelfField(id: String, build: ContentValues.() -> Unit) {
        val values = ContentValues().apply(build)
        values.put("updated_at", now())
        db.update("shelves", values, "id = ?", arrayOf(id))
    }

    suspend fun addShelf(name: String, theme: ShelfTheme) = write {
        insertShelf(name, state.value.shelves.maxOfOrNull { it.order + 1 } ?: 0, theme, null)
    }

    suspend fun renameShelf(id: String, name: String) = write { updateShelfField(id) { put("name", name) } }

    suspend fun setShelfTheme(id: String, theme: ShelfTheme) =
        write { updateShelfField(id) { put("theme", ThemeJson.write(theme)) } }

    suspend fun setShelfView(id: String, mode: ViewMode) =
        write { updateShelfField(id) { put("view_mode", mode.name) } }

    suspend fun setShelfSort(id: String, sort: SortOrder) =
        write { updateShelfField(id) { put("sort_order", sort.name) } }

    suspend fun setShelfGrouping(id: String, group: Boolean) =
        write { updateShelfField(id) { put("group_series", if (group) 1 else 0) } }

    /** Moves a shelf up or down, which sets both the home buttons and the swipe order. */
    suspend fun moveShelf(id: String, delta: Int) = write {
        val order = state.value.shelves.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = from + delta
        if (from >= 0 && to in order.indices) {
            order.add(to, order.removeAt(from))
            order.forEachIndexed { index, shelfId -> updateShelfField(shelfId) { put("sort_position", index) } }
        }
    }

    /** Removes a shelf. Its books stay in the library. */
    suspend fun deleteShelf(id: String) = write {
        val time = now()
        updateShelfField(id) { put("deleted_at", time) }
        db.execSQL(Schema.REMOVE_SHELF_MEMBERSHIPS, arrayOf<Any>(time, time, id))
    }

    suspend fun setHomeTheme(theme: ShelfTheme) = write { saveSetting(KEY_HOME_THEME, ThemeJson.write(theme)) }

    /** Applies one edit to every volume. Matching an existing series of the same type merges the two. */
    suspend fun updateSeries(seriesId: String, edit: SeriesEdit) = write {
        val time = now()
        val title = edit.title.trim()
        val type = edit.type ?: state.value.books.firstOrNull { it.seriesId == seriesId }?.type ?: BookType.Book
        val key = seriesMatchKey(title, type)
        val match = db.rawQuery(Schema.SELECT_SERIES_BY_KEY, arrayOf(key)).use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
        val targetId = if (match == null || match == seriesId) {
            val values = ContentValues().apply {
                put("title", title)
                put("match_key", key)
                put("updated_at", time)
            }
            db.update("series", values, "id = ?", arrayOf(seriesId))
            seriesId
        } else {
            match
        }

        val bookIds = state.value.books.filter { it.seriesId == seriesId }.map { it.id }
        val values = ContentValues().apply {
            put("series_id", targetId)
            put("updated_at", time)
            edit.author?.let { put("author", it) }
            edit.type?.let { put("type", it.name) }
            edit.direction?.let { put("direction", it.name) }
        }
        db.update("books", values, "series_id = ? AND deleted_at IS NULL", arrayOf(seriesId))
        for (bookId in bookIds) {
            edit.removeShelves.forEach { db.execSQL(Schema.REMOVE_SHELF_BOOK, arrayOf<Any>(time, time, it, bookId)) }
            edit.addShelves.forEach { db.execSQL(Schema.UPSERT_SHELF_BOOK, arrayOf<Any>(it, bookId, time)) }
        }
        db.execSQL(Schema.DELETE_EMPTY_SERIES, arrayOf<Any>(time, time))
    }

    /** Records where books moved to in the library folder. */
    suspend fun setDocumentUris(moved: Map<String, String>) = write {
        moved.forEach { (id, uri) ->
            val values = ContentValues().apply {
                put("document_uri", uri)
                put("updated_at", now())
            }
            db.update("books", values, "id = ?", arrayOf(id))
        }
    }

    suspend fun setGlobalPrefs(prefs: ReaderPrefs) = write { saveSetting(KEY_PREFS_GLOBAL, writePrefs(prefs)) }

    suspend fun setTypePrefs(type: BookType, prefs: ReaderPrefs) =
        write { saveSetting(KEY_PREFS_TYPE + type.name, writePrefs(prefs)) }

    private fun updateBookField(id: String, build: ContentValues.() -> Unit) {
        val values = ContentValues().apply(build)
        values.put("updated_at", now())
        db.update("books", values, "id = ?", arrayOf(id))
    }

    /** Saves a change at the chosen level, clearing narrower levels that would hide it. */
    suspend fun changePrefs(book: Book, scope: SettingScope, change: (ReaderPrefs) -> ReaderPrefs) = write {
        val current = state.value
        val bookPrefs = current.book(book.id)?.readerPrefs ?: book.readerPrefs
        val changed = change(ReaderPrefs())
        when (scope) {
            SettingScope.ThisBook -> saveBookPrefs(book.id, change(bookPrefs))
            SettingScope.ThisType -> {
                val typePrefs = current.typePrefs[book.type] ?: ReaderPrefs()
                saveSetting(KEY_PREFS_TYPE + book.type.name, writePrefs(change(typePrefs)))
                saveBookPrefs(book.id, bookPrefs.clearFieldsSetIn(changed))
            }
            SettingScope.AllBooks -> {
                saveSetting(KEY_PREFS_GLOBAL, writePrefs(change(current.globalPrefs)))
                current.typePrefs.forEach { (type, prefs) ->
                    saveSetting(KEY_PREFS_TYPE + type.name, writePrefs(prefs.clearFieldsSetIn(changed)))
                }
                saveBookPrefs(book.id, bookPrefs.clearFieldsSetIn(changed))
            }
        }
    }

    private fun saveBookPrefs(id: String, prefs: ReaderPrefs) =
        updateBookField(id) { put("reader_prefs", writePrefs(prefs)) }

    suspend fun addColorSet(set: ColorSet) = saveColorSets { it + set }

    suspend fun removeColorSet(set: ColorSet) = saveColorSets { it - set }

    private suspend fun saveColorSets(change: (List<ColorSet>) -> List<ColorSet>) = write {
        val rows = JSONArray()
        change(state.value.colorSets).distinct().forEach { set ->
            rows.put(JSONObject().apply {
                put("background", set.background)
                put("text", set.text)
            })
        }
        saveSetting(KEY_COLOR_SETS, rows.toString())
    }

    suspend fun setBookPrefs(id: String, prefs: ReaderPrefs) =
        write { updateBookField(id) { put("reader_prefs", writePrefs(prefs)) } }

    suspend fun setSpreadShift(id: String, shift: Boolean) =
        write { updateBookField(id) { put("spread_shift", if (shift) 1 else 0) } }

    suspend fun setDirection(id: String, direction: ReadDirection) =
        write { updateBookField(id) { put("direction", direction.name) } }

    suspend fun addBookmark(bookId: String, page: Int, label: String) = write {
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("book_id", bookId)
            put("page", page)
            put("label", label)
            put("created_at", now())
            put("updated_at", now())
        }
        db.insertOrThrow("bookmarks", null, values)
    }

    suspend fun removeBookmark(id: String) = write {
        val time = now()
        db.execSQL(Schema.REMOVE_BOOKMARK, arrayOf<Any>(time, time, id))
    }

    /** Puts a book in a series, for when a later import shows which series it belongs to. */
    suspend fun assignSeries(id: String, seriesTitle: String, number: Double?) = write {
        val type = state.value.books.firstOrNull { it.id == id }?.type ?: BookType.Book
        val values = ContentValues().apply {
            put("series_id", seriesIdFor(seriesTitle.trim(), type))
            put("series_number", number)
            put("updated_at", now())
        }
        db.update("books", values, "id = ?", arrayOf(id))
    }

    private fun seriesIdFor(title: String, type: BookType): String {
        val key = seriesMatchKey(title, type)
        db.rawQuery(Schema.SELECT_SERIES_BY_KEY, arrayOf(key)).use { rows ->
            if (rows.moveToFirst()) return rows.getString(0)
        }
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("match_key", key)
            put("updated_at", now())
        }
        db.insertOrThrow("series", null, values)
        return id
    }

    suspend fun addBook(book: Book, seriesTitle: String?, shelfIds: Collection<String>) = write {
        val seriesId = seriesTitle?.takeIf { it.isNotBlank() }?.let { seriesIdFor(it.trim(), book.type) }
        val values = ContentValues().apply {
            put("id", book.id)
            put("hash", book.hash)
            put("type", book.type.name)
            put("format", book.format)
            put("title", book.title)
            put("author", book.author)
            put("series_id", seriesId)
            put("series_number", book.seriesNumber)
            put("document_uri", book.documentUri)
            put("is_folder", if (book.isFolder) 1 else 0)
            put("page_count", book.pageCount)
            put("cover_path", book.coverPath)
            put("added_at", book.addedAt)
            put("updated_at", book.updatedAt)
            put("in_read_list", if (book.inReadList) 1 else 0)
            put("direction", book.direction.name)
        }
        db.insertOrThrow("books", null, values)
        shelfIds.forEach { db.execSQL(Schema.UPSERT_SHELF_BOOK, arrayOf<Any>(it, book.id, now())) }
    }

    suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        seriesTitle: String,
        seriesNumber: Double?,
        type: BookType,
        direction: ReadDirection,
    ) = write {
        val seriesId = seriesTitle.takeIf { it.isNotBlank() }?.let { seriesIdFor(it.trim(), type) }
        val values = ContentValues().apply {
            put("title", title)
            put("author", author)
            put("series_id", seriesId)
            put("series_number", seriesNumber)
            put("type", type.name)
            put("direction", direction.name)
            put("updated_at", now())
        }
        db.update("books", values, "id = ?", arrayOf(id))
        val time = now()
        db.execSQL(Schema.DELETE_EMPTY_SERIES, arrayOf<Any>(time, time))
    }

    suspend fun setReadList(id: String, inList: Boolean) = write {
        val values = ContentValues().apply {
            put("in_read_list", if (inList) 1 else 0)
            put("updated_at", now())
        }
        db.update("books", values, "id = ?", arrayOf(id))
    }

    suspend fun setBookShelves(bookId: String, shelfIds: Set<String>) = write {
        val time = now()
        val current = state.value.shelfBooks.filterValues { bookId in it }.keys
        (current - shelfIds).forEach { db.execSQL(Schema.REMOVE_SHELF_BOOK, arrayOf<Any>(time, time, it, bookId)) }
        (shelfIds - current).forEach { db.execSQL(Schema.UPSERT_SHELF_BOOK, arrayOf<Any>(it, bookId, time)) }
    }

    /** Marks a book deleted. The marker stays so that sync can pass the deletion to other devices. */
    suspend fun deleteBook(id: String) = write {
        val time = now()
        val values = ContentValues().apply {
            put("deleted_at", time)
            put("updated_at", time)
        }
        db.update("books", values, "id = ?", arrayOf(id))
        db.execSQL(Schema.REMOVE_BOOK_MEMBERSHIPS, arrayOf<Any>(time, time, id))
        db.execSQL(Schema.DELETE_EMPTY_SERIES, arrayOf<Any>(time, time))
    }

    /** Saves a reading position. Runs on every page turn, so it patches the snapshot directly. */
    suspend fun savePosition(id: String, position: Int, progress: Float, locator: String? = null) =
        withContext(serial) {
            val time = now()
            val values = ContentValues().apply {
                put("position", position)
                put("progress", progress)
                put("locator", locator)
                put("last_read_at", time)
                put("updated_at", time)
            }
            db.update("books", values, "id = ?", arrayOf(id))
            mutableState.update { current ->
                current.copy(
                    books = current.books.map { book ->
                        if (book.id == id) {
                            book.copy(
                                position = position,
                                progress = progress,
                                locator = locator,
                                lastReadAt = time,
                                updatedAt = time,
                            )
                        } else {
                            book
                        }
                    },
                )
            }
        }

    companion object {
        private const val KEY_SEEDED = "seeded"
        private const val KEY_HOME_THEME = "home_theme"
        private const val KEY_PREFS_GLOBAL = "reader_prefs"
        private const val KEY_PREFS_TYPE = "reader_prefs:"
        private const val KEY_COLOR_SETS = "color_sets"

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(context).also { instance = it }
            }
    }
}
