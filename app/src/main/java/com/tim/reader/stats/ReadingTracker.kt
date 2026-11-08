package com.tim.reader.stats

import android.content.Context
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.EventKind
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Records reading sessions for the stats page. Both readers report the book they open and each
 * page turn, and the activities report when the app is paused and resumed.
 *
 * Only active time counts. The time spent on any one page is capped, so a book left open on the
 * nightstand doesn't count as hours of reading. A pause ends the session, and resuming starts a new
 * one from the same place.
 */
object ReadingTracker {
    private const val PAGE_CAP_MS = 3 * 60_000L
    private const val MIN_SESSION_MS = 5_000L
    private const val FINISHED = 0.999f

    // One at a time and in order, so a read is always started before it's finished.
    private val writes = Dispatchers.IO.limitedParallelism(1)

    private class Session(val book: Book, val total: Int, val from: Int, val fromProgress: Float) {
        val startedAt = System.currentTimeMillis()
        var lastActivity = startedAt
        var activeMs = 0L
        var to = from
        var toProgress = fromProgress
        var turns = 0
    }

    private var repository: LibraryRepository? = null
    private var book: Book? = null
    private var total = 0
    private var position = 0
    private var progress = 0f
    private var session: Session? = null
    private var readChecked = false
    private var finishLogged = false

    /**
     * A reader opened a book. [total] and [position] are pages for the image reader, and Readium
     * positions (about 1,000 characters each) for EPUBs.
     */
    @Synchronized
    fun start(context: Context, book: Book, total: Int, position: Int, progress: Float) {
        endSession()
        repository = LibraryRepository.get(context)
        this.book = book
        this.total = total
        this.position = position
        this.progress = progress
        readChecked = false
        // Opening a book that's already at its end doesn't finish it again.
        finishLogged = progress >= FINISHED
        session = Session(book, total, position, progress)
    }

    /** The page or position changed. Repeats of the same place are ignored. */
    @Synchronized
    fun turn(position: Int, progress: Float) {
        val current = session ?: return
        val book = book ?: return
        if (position == current.to) return
        val now = System.currentTimeMillis()
        current.activeMs += minOf(now - current.lastActivity, PAGE_CAP_MS)
        current.lastActivity = now
        current.turns++
        current.to = position
        current.toProgress = progress
        this.position = position
        this.progress = progress

        // Going back to near the start of a finished book can begin a reread.
        if (progress < EventKind.REREAD_BEFORE) finishLogged = false
        if (!readChecked) {
            readChecked = true
            record { it.startReadIfNeeded(book, progress, now) }
        }
        if (!finishLogged && progress >= FINISHED) {
            finishLogged = true
            readChecked = false
            record { it.finishRead(book, now) }
        }
    }

    /** The app went to the background or the screen turned off. */
    @Synchronized
    fun pause() = endSession()

    /** The app came back, so reading carries on from the same place. */
    @Synchronized
    fun resume() {
        val book = book ?: return
        if (session == null) session = Session(book, total, position, progress)
    }

    /** The reader closed. */
    @Synchronized
    fun stop() {
        endSession()
        book = null
    }

    private fun endSession() {
        val ended = session ?: return
        session = null
        val now = System.currentTimeMillis()
        ended.activeMs += minOf(now - ended.lastActivity, PAGE_CAP_MS)
        if (ended.activeMs < MIN_SESSION_MS) return
        val data = JSONObject().apply {
            put("type", ended.book.type.name)
            put("format", ended.book.format)
            put("active", ended.activeMs)
            put("from", ended.from)
            put("to", ended.to)
            put("total", ended.total)
            put("fromProgress", ended.fromProgress.toDouble())
            put("toProgress", ended.toProgress.toDouble())
            put("turns", ended.turns)
        }
        record { it.addEvent(EventKind.SESSION, ended.book, null, ended.startedAt, now, data) }
    }

    private fun record(write: suspend (LibraryRepository) -> Unit) {
        val target = repository ?: return
        appScope.launch(writes) { write(target) }
    }
}
