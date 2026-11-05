package com.tim.reader.data

/**
 * Every row carries an ID and timestamps, and deleted rows stay as markers, so Phase 4 sync can add
 * to this schema rather than replace it.
 */
object Schema {
    const val VERSION = 2

    const val CREATE_BOOKS = """
        CREATE TABLE books (
            id TEXT PRIMARY KEY NOT NULL,
            hash TEXT NOT NULL,
            type TEXT NOT NULL,
            format TEXT NOT NULL,
            title TEXT NOT NULL,
            author TEXT NOT NULL DEFAULT '',
            series_id TEXT,
            series_number REAL,
            document_uri TEXT NOT NULL,
            is_folder INTEGER NOT NULL DEFAULT 0,
            page_count INTEGER NOT NULL DEFAULT 0,
            cover_path TEXT,
            custom_cover_path TEXT,
            added_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            last_read_at INTEGER,
            position INTEGER NOT NULL DEFAULT 0,
            locator TEXT,
            progress REAL NOT NULL DEFAULT 0,
            in_read_list INTEGER NOT NULL DEFAULT 0,
            direction TEXT NOT NULL
        )"""

    const val CREATE_BOOKS_HASH_INDEX = "CREATE INDEX books_hash ON books (hash)"

    const val CREATE_SERIES = """
        CREATE TABLE series (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            match_key TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER
        )"""

    const val CREATE_SERIES_KEY_INDEX = "CREATE INDEX series_key ON series (match_key)"

    const val CREATE_SHELVES = """
        CREATE TABLE shelves (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            sort_position INTEGER NOT NULL,
            theme TEXT NOT NULL,
            view_mode TEXT NOT NULL,
            sort_order TEXT NOT NULL,
            group_series INTEGER NOT NULL DEFAULT 1,
            default_type TEXT,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER
        )"""

    const val CREATE_SHELF_BOOKS = """
        CREATE TABLE shelf_books (
            shelf_id TEXT NOT NULL,
            book_id TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            PRIMARY KEY (shelf_id, book_id)
        )"""

    const val CREATE_SETTINGS = """
        CREATE TABLE settings (
            key TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )"""

    const val ADD_BOOK_READER_PREFS = "ALTER TABLE books ADD COLUMN reader_prefs TEXT"
    const val ADD_BOOK_SPREAD_SHIFT = "ALTER TABLE books ADD COLUMN spread_shift INTEGER NOT NULL DEFAULT 0"

    const val CREATE_BOOKMARKS = """
        CREATE TABLE bookmarks (
            id TEXT PRIMARY KEY NOT NULL,
            book_id TEXT NOT NULL,
            page INTEGER NOT NULL,
            label TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER
        )"""

    /** Each list brings a database from the version before it up to its version. */
    val UPGRADE_TO_2 = listOf(ADD_BOOK_READER_PREFS, ADD_BOOK_SPREAD_SHIFT, CREATE_BOOKMARKS)

    val CREATE_ALL = listOf(
        CREATE_BOOKS,
        CREATE_BOOKS_HASH_INDEX,
        CREATE_SERIES,
        CREATE_SERIES_KEY_INDEX,
        CREATE_SHELVES,
        CREATE_SHELF_BOOKS,
        CREATE_SETTINGS,
    )

    const val BOOK_COLUMNS = "id, hash, type, format, title, author, series_id, series_number, document_uri, " +
        "is_folder, page_count, cover_path, custom_cover_path, added_at, updated_at, last_read_at, position, " +
        "locator, progress, in_read_list, direction, reader_prefs, spread_shift"

    const val SELECT_BOOKS = "SELECT $BOOK_COLUMNS FROM books WHERE deleted_at IS NULL"
    const val SELECT_SERIES = "SELECT id, title, updated_at FROM series WHERE deleted_at IS NULL"
    const val SELECT_SHELVES = "SELECT id, name, sort_position, theme, view_mode, sort_order, group_series, " +
        "default_type, updated_at FROM shelves WHERE deleted_at IS NULL ORDER BY sort_position"
    const val SELECT_SHELF_BOOKS = "SELECT shelf_id, book_id FROM shelf_books WHERE deleted_at IS NULL"
    const val SELECT_SETTING = "SELECT value FROM settings WHERE key = ?"
    const val SELECT_BOOKMARKS = "SELECT id, book_id, page, label, created_at FROM bookmarks " +
        "WHERE deleted_at IS NULL ORDER BY page"
    const val REMOVE_BOOKMARK = "UPDATE bookmarks SET deleted_at = ?, updated_at = ? WHERE id = ?"
    const val SELECT_SERIES_BY_KEY = "SELECT id FROM series WHERE match_key = ? AND deleted_at IS NULL LIMIT 1"
    const val DELETE_EMPTY_SERIES = "UPDATE series SET deleted_at = ?, updated_at = ? WHERE deleted_at IS NULL " +
        "AND id NOT IN (SELECT series_id FROM books WHERE series_id IS NOT NULL AND deleted_at IS NULL)"
    const val UPSERT_SETTING = "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) " +
        "ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at"
    const val UPSERT_SHELF_BOOK = "INSERT INTO shelf_books (shelf_id, book_id, updated_at, deleted_at) " +
        "VALUES (?, ?, ?, NULL) ON CONFLICT (shelf_id, book_id) " +
        "DO UPDATE SET updated_at = excluded.updated_at, deleted_at = NULL"
    const val REMOVE_SHELF_BOOK = "UPDATE shelf_books SET deleted_at = ?, updated_at = ? " +
        "WHERE shelf_id = ? AND book_id = ? AND deleted_at IS NULL"
    const val REMOVE_BOOK_MEMBERSHIPS = "UPDATE shelf_books SET deleted_at = ?, updated_at = ? " +
        "WHERE book_id = ? AND deleted_at IS NULL"
    const val REMOVE_SHELF_MEMBERSHIPS = "UPDATE shelf_books SET deleted_at = ?, updated_at = ? " +
        "WHERE shelf_id = ? AND deleted_at IS NULL"
}
