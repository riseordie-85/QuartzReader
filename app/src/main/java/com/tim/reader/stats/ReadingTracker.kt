package com.tim.reader.stats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.EventKind
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Records reading sessions for the stats page. Both readers report the book they open and each
 * page turn, and their activity reports when it's paused and resumed.
 *
 * Only active time counts. The time spent on any one page is capped, so a book left open on the
 * nightstand doesn't count as hours of reading. A pause ends the session, and resuming starts a new
 * one from the same place. Calls from an activity other than the one reading are ignored.
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

    /** The book just finished, until the library has shown its Finished card. Set once the reader closes. */
    val finished = MutableStateFlow<String?>(null)

    // A finish waiting for the reader to close, so the card's time includes the last session.
    @Volatile
    private var finishPending: String? = null
    private var finishing: Job? = null

    private var owner: ComponentActivity? = null
    private var appContext: Context? = null
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
    fun start(activity: ComponentActivity, book: Book, total: Int, position: Int, progress: Float) {
        // A reader that finishes opening after it was closed must not start tracking.
        if (activity.isFinishing || activity.isDestroyed) return
        endSession()
        owner = activity
        repository = LibraryRepository.get(activity)
        appContext = activity.applicationContext
        this.book = book
        this.total = total
        this.position = position
        this.progress = progress
        readChecked = false
        finishLogged = false
        // A reader that finished opening while the app was in the background waits for resume.
        session = if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Session(book, total, position, progress)
        } else {
            null
        }
        // A one-page book has nothing to turn, so opening it is reading it.
        if (total <= 1 && progress < FINISHED) {
            checkRead(book, progress, System.currentTimeMillis())
            finish(book, System.currentTimeMillis())
        } else if (progress >= FINISHED) {
            // Opening a book already at its end doesn't finish it again.
            finishLogged = true
        }
    }

    /** The page or position changed. Repeats of the same place are ignored. */
    @Synchronized
    fun turn(activity: ComponentActivity, position: Int, progress: Float, atEnd: Boolean = progress >= FINISHED) {
        if (activity !== owner) return
        val book = book ?: return
        if (position == this.position) return
        val now = System.currentTimeMillis()
        session?.let { current ->
            current.activeMs += minOf(now - current.lastActivity, PAGE_CAP_MS)
            current.lastActivity = now
            current.turns++
            current.to = position
            current.toProgress = progress
        }
        val before = this.progress
        this.position = position
        this.progress = progress

        // Going back to near the start of a finished book can begin a reread.
        if (before >= EventKind.REREAD_BEFORE && progress < EventKind.REREAD_BEFORE) {
            finishLogged = false
            readChecked = false
        }
        checkRead(book, progress, now)
        if (!finishLogged && atEnd) finish(book, now)
    }

    /** The activity went to the background or the screen turned off. */
    @Synchronized
    fun pause(activity: ComponentActivity) {
        if (activity === owner) endSession()
    }

    /** The activity came back, so reading carries on from the same place. */
    @Synchronized
    fun resume(activity: ComponentActivity) {
        if (activity !== owner) return
        val book = book ?: return
        if (session == null) session = Session(book, total, position, progress)
    }

    /** The reader closed. */
    @Synchronized
    fun stop(activity: ComponentActivity) {
        if (activity !== owner) return
        endSession()
        val waitFor = finishing
        finishing = null
        record { repository ->
            waitFor?.join()
            repository.awaitWrites()
            finishPending?.let { finished.value = it }
            finishPending = null
        }
        book = null
        owner = null
    }

    private fun checkRead(book: Book, progress: Float, now: Long) {
        if (readChecked) return
        readChecked = true
        record { it.startReadIfNeeded(book, progress, now) }
    }

    private fun finish(book: Book, now: Long) {
        finishLogged = true
        readChecked = false
        finishing = record { repository ->
            // A reread that never started (opened near the end) finishes nothing, so it gets no card.
            if (repository.finishRead(book, now)) finishPending = book.id
            appContext?.let(AchievementCenter::check)
        }
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
        record { repository ->
            repository.addEvent(EventKind.SESSION, ended.book, null, ended.startedAt, now, data)
            appContext?.let(AchievementCenter::check)
        }
    }

    private fun record(write: suspend (LibraryRepository) -> Unit): Job? {
        val target = repository ?: return null
        return appScope.launch(writes) { write(target) }
    }
}
