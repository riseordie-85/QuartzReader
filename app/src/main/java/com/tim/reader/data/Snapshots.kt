package com.tim.reader.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What merging another device's snapshot changed, and covers it needs fetched from the library folder. */
class MergeResult(val changed: Int, val covers: List<Pair<String, String>>)

/**
 * Turns the library database into a snapshot for the library folder, and merges a snapshot from
 * another device (or from before a reinstall) back in.
 *
 * Every row has its own ID and an updated_at time, so most things merge by keeping the newest.
 * Books are matched by their file hash, since each device gives a book its own ID. Shelves match by
 * ID, then by the type they hold, then by name. Series match by their title. A reading position
 * goes with whichever device read last. On a fresh install with no reading history yet, everything
 * from the snapshot is taken, since the local library is only defaults.
 */
internal class SnapshotCodec(private val db: SQLiteDatabase) {

    fun export(device: String, deviceName: String, time: Long): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("device", device)
        put("deviceName", deviceName)
        put("written", time)
        put("books", rows("SELECT * FROM books") { row ->
            row.remove("document_uri")
            row.remove("cover_path")
            val custom = row.optString("custom_cover_path").takeIf { it.isNotEmpty() && it != "null" }
            row.remove("custom_cover_path")
            custom?.let { row.put("custom_cover", File(it).name) }
        })
        put("series", rows("SELECT * FROM series"))
        put("shelves", rows("SELECT * FROM shelves"))
        put("shelf_books", rows("SELECT * FROM shelf_books"))
        put("bookmarks", rows("SELECT * FROM bookmarks"))
        put("highlights", rows("SELECT * FROM highlights"))
        put("events", rows("SELECT * FROM events"))
        put("settings", rows("SELECT * FROM settings WHERE key <> 'seeded'"))
    }

    private fun rows(sql: String, adjust: (JSONObject) -> Unit = {}): JSONArray = JSONArray().also { array ->
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) array.put(rowOf(cursor).also(adjust))
        }
    }

    private fun rowOf(cursor: Cursor): JSONObject = JSONObject().also { row ->
        for (index in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(index)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                else -> row.put(name, cursor.getString(index))
            }
        }
    }

    /** Merges [remote] into the database. Runs inside one transaction. */
    fun merge(remote: JSONObject): MergeResult {
        var changed = 0
        val covers = ArrayList<Pair<String, String>>()
        val fresh = count("SELECT COUNT(*) FROM events") == 0L

        db.beginTransaction()
        try {
            // Series, matched by title.
            val seriesIds = HashMap<String, String>()
            val remoteSeries = remote.optJSONArray("series").objects().associateBy { it.getString("id") }
            fun localSeries(remoteId: String?): String? {
                if (remoteId == null) return null
                seriesIds[remoteId]?.let { return it }
                val row = remoteSeries[remoteId] ?: return null
                val key = row.optString("match_key")
                val found = single("SELECT id FROM series WHERE match_key = ? AND deleted_at IS NULL", key)
                val id = found ?: remoteId.also {
                    db.insertWithOnConflict("series", null, values(row, listOf("id", "title", "match_key", "updated_at", "deleted_at")), SQLiteDatabase.CONFLICT_IGNORE)
                }
                seriesIds[remoteId] = id
                return id
            }

            // Books, matched by hash. Books whose file isn't here are left for when it is.
            val bookIds = HashMap<String, String>()
            for (row in remote.optJSONArray("books").objects()) {
                val local = db.rawQuery(
                    "SELECT id, updated_at, last_read_at, custom_cover_path, added_at FROM books WHERE hash = ? AND deleted_at IS NULL LIMIT 1",
                    arrayOf(row.getString("hash")),
                ).use { if (it.moveToFirst()) LocalBook(it.getString(0), it.getLong(1), it.longOrNull(2), it.getString(3), it.getLong(4)) else null } ?: continue
                bookIds[row.getString("id")] = local.id

                val update = ContentValues()
                // A book just added here, such as after a reinstall, has nothing of its own to keep.
                val untouched = local.updatedAt <= local.addedAt + UNTOUCHED_MS
                if (fresh || untouched || row.optLong("updated_at") > local.updatedAt) {
                    for (column in listOf("title", "author", "series_number", "in_read_list", "direction", "reader_prefs", "spread_shift", "info", "type", "updated_at")) {
                        if (row.has(column)) update.putJson(column, row.opt(column))
                    }
                    update.put("series_id", localSeries(row.optStringOrNull("series_id")))
                    val cover = row.optStringOrNull("custom_cover")
                    if (cover != null && cover != local.customCover?.let { File(it).name }) covers += local.id to cover
                }
                val remoteRead = row.optLongOrNull("last_read_at")
                if (remoteRead != null && remoteRead > (local.lastReadAt ?: 0L)) {
                    for (column in listOf("position", "locator", "progress", "last_read_at")) if (row.has(column)) update.putJson(column, row.opt(column))
                }
                if (update.size() > 0 && db.update("books", update, "id = ?", arrayOf(local.id)) > 0) changed++
            }

            // Shelves, by ID, then by the type they hold, then by name.
            val shelfIds = HashMap<String, String>()
            val shelfColumns = listOf("name", "sort_position", "theme", "view_mode", "sort_order", "group_series", "default_type", "updated_at", "deleted_at", "reader_prefs", "default_direction")
            for (row in remote.optJSONArray("shelves").objects()) {
                val remoteId = row.getString("id")
                val type = row.optStringOrNull("default_type")
                val localId = single("SELECT id FROM shelves WHERE id = ?", remoteId)
                    ?: type?.let { single("SELECT id FROM shelves WHERE default_type = ? AND deleted_at IS NULL", it) }
                    ?: single("SELECT id FROM shelves WHERE lower(name) = lower(?) AND deleted_at IS NULL", row.optString("name"))
                if (localId == null) {
                    if (row.isNull("deleted_at")) {
                        db.insertWithOnConflict("shelves", null, values(row, listOf("id") + shelfColumns), SQLiteDatabase.CONFLICT_IGNORE)
                        changed++
                    }
                    shelfIds[remoteId] = remoteId
                    continue
                }
                shelfIds[remoteId] = localId
                val localUpdated = count("SELECT updated_at FROM shelves WHERE id = ?", localId)
                if (fresh || row.optLong("updated_at") > localUpdated) {
                    if (db.update("shelves", values(row, shelfColumns), "id = ?", arrayOf(localId)) > 0) changed++
                }
            }

            // Which books are on which shelves.
            for (row in remote.optJSONArray("shelf_books").objects()) {
                val shelf = shelfIds[row.getString("shelf_id")] ?: continue
                val book = bookIds[row.getString("book_id")] ?: continue
                val localUpdated = db.rawQuery("SELECT updated_at FROM shelf_books WHERE shelf_id = ? AND book_id = ?", arrayOf(shelf, book))
                    .use { if (it.moveToFirst()) it.getLong(0) else null }
                if (localUpdated == null || fresh || row.optLong("updated_at") > localUpdated) {
                    val values = ContentValues().apply {
                        put("shelf_id", shelf)
                        put("book_id", book)
                        put("updated_at", row.optLong("updated_at"))
                        putJson("deleted_at", row.opt("deleted_at"))
                    }
                    db.insertWithOnConflict("shelf_books", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    changed++
                }
            }

            // Bookmarks, highlights and history keep their IDs everywhere.
            changed += mergeById("bookmarks", remote.optJSONArray("bookmarks"), listOf("book_id", "page", "label", "created_at", "updated_at", "deleted_at")) { row ->
                bookIds[row.getString("book_id")]?.also { row.put("book_id", it) } != null
            }
            changed += mergeById("highlights", remote.optJSONArray("highlights"), listOf("book_id", "locator", "text", "note", "created_at", "updated_at", "deleted_at")) { row ->
                bookIds[row.getString("book_id")]?.also { row.put("book_id", it) } != null
            }
            changed += mergeById(
                "events",
                remote.optJSONArray("events"),
                listOf("kind", "book_id", "book_hash", "read_id", "started_at", "ended_at", "data", "device", "updated_at", "deleted_at"),
            ) { row ->
                // History is kept even for books not in this library, matched again by hash later.
                val hash = row.optStringOrNull("book_hash")
                val local = hash?.let { single("SELECT id FROM books WHERE hash = ? AND deleted_at IS NULL", it) }
                row.put("book_id", local ?: JSONObject.NULL)
                true
            }

            // Settings, by key.
            for (row in remote.optJSONArray("settings").objects()) {
                val key = row.getString("key")
                val localUpdated = db.rawQuery("SELECT updated_at FROM settings WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (localUpdated == null || fresh || row.optLong("updated_at") > localUpdated) {
                    db.insertWithOnConflict("settings", null, values(row, listOf("key", "value", "updated_at")), SQLiteDatabase.CONFLICT_REPLACE)
                    changed++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MergeResult(changed, covers)
    }

    /** Adds rows this device doesn't have, and takes newer versions of ones it does. */
    private fun mergeById(table: String, rows: JSONArray?, columns: List<String>, accept: (JSONObject) -> Boolean): Int {
        var changed = 0
        for (row in rows.objects()) {
            if (!accept(row)) continue
            val id = row.getString("id")
            val localUpdated = db.rawQuery("SELECT updated_at FROM $table WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else null }
            when {
                localUpdated == null -> {
                    db.insertWithOnConflict(table, null, values(row, listOf("id") + columns), SQLiteDatabase.CONFLICT_IGNORE)
                    changed++
                }
                row.optLong("updated_at") > localUpdated -> {
                    db.update(table, values(row, columns), "id = ?", arrayOf(id))
                    changed++
                }
            }
        }
        return changed
    }

    private class LocalBook(val id: String, val updatedAt: Long, val lastReadAt: Long?, val customCover: String?, val addedAt: Long)

    private fun values(row: JSONObject, columns: List<String>) = ContentValues().apply {
        for (column in columns) if (row.has(column)) putJson(column, row.opt(column))
    }

    private fun ContentValues.putJson(column: String, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> putNull(column)
            is Int -> put(column, value.toLong())
            is Long -> put(column, value)
            is Double -> put(column, value)
            is Float -> put(column, value.toDouble())
            is Boolean -> put(column, if (value) 1L else 0L)
            else -> put(column, value.toString())
        }
    }

    private fun single(sql: String, vararg args: String): String? =
        db.rawQuery(sql, arrayOf(*args)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun count(sql: String, vararg args: String): Long =
        db.rawQuery(sql, arrayOf(*args)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    private fun Cursor.longOrNull(index: Int) = if (isNull(index)) null else getLong(index)

    companion object {
        const val VERSION = 1

        // Adding a book takes a moment, so a book changed within this of being added counts as untouched.
        private const val UNTOUCHED_MS = 5_000L
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else optLong(name)
