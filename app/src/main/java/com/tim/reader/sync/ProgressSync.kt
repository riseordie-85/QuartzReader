package com.tim.reader.sync

import android.content.Context
import android.net.Uri
import com.tim.reader.ResultsLog
import com.tim.reader.appScope
import com.tim.reader.data.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where this device is in a book, ready to send. */
data class LocalSpot(val progress: String, val percentage: Double)

/** How another device's place compares with what's on screen here. */
sealed interface Comparison {
    /** The other device is further on. [go] moves there. */
    class Ahead(val go: suspend () -> Unit) : Comparison
    data object Same : Comparison
    data object Behind : Comparison
}

/** What a reader does for sync: compare another device's place with its own, and go there. */
interface SyncReader {
    suspend fun compare(remote: RemoteProgress): Comparison
}

/** A question for the reader: another device is further on, so go there? */
data class SyncOffer(val device: String, val percent: Int, val manual: Boolean)

/**
 * Keeps one open book in step with the sync server. It looks for a newer place when the book
 * opens and when the reader comes back to it, and sends this device's place a little after the
 * pages stop turning and when the reader leaves.
 *
 * It won't send anything until it knows the server's place, so opening a book here can't
 * overwrite further progress made on the Kindle.
 */
class ProgressSync(
    context: Context,
    private val book: Book,
    private val reader: SyncReader,
    /** Shows a short message, from any thread. */
    private val tell: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val _offer = MutableStateFlow<SyncOffer?>(null)
    val offer: StateFlow<SyncOffer?> = _offer
    private var pendingGo: (suspend () -> Unit)? = null

    /** True once this device may send its place: the server's has been seen and settled. */
    @Volatile private var settled = false
    @Volatile private var pullFailed = false
    @Volatile private var spot: LocalSpot? = null
    @Volatile private var sent: LocalSpot? = null
    /** After jumping to another device's place, that device already has it, so it isn't sent back. */
    @Volatile private var skipNext = false
    private var pushJob: Job? = null
    private var pausedAt = 0L
    private val lock = Mutex()
    private var documentId: String? = null

    private val active get() = CrossPointSettings.isReady

    private suspend fun document(): String? = documentId ?: withContext(Dispatchers.IO) {
        val uri = Uri.parse(book.documentUri)
        (if (CrossPointSettings.matchByName) fileNameDocumentId(context, uri) else koreaderDocumentId(context, uri))
            .also { documentId = it }
    }

    /** Call once the book is open and its first page is showing. */
    fun start(scope: CoroutineScope) {
        CrossPointSettings.ensureLoaded(context)
        if (!active) return
        scope.launch { pull(manual = false) }
    }

    /** Call whenever this device's place changes. */
    fun update(newSpot: LocalSpot) {
        spot = newSpot
        if (skipNext) {
            skipNext = false
            sent = newSpot
            return
        }
        if (pullFailed && !settled && sent == null) {
            // The server couldn't be reached at open. Reading on means this device's place is the one to keep.
            settled = true
        }
        if (!active || !settled || newSpot == sent) return
        pushJob?.cancel()
        pushJob = appScope.launch {
            delay(PUSH_DELAY_MS)
            push()
        }
    }

    /** Call when the reader leaves the book or the screen goes off. */
    fun paused() {
        pausedAt = System.currentTimeMillis()
        pushJob?.cancel()
        if (active && settled) appScope.launch { push() }
    }

    /** Call when the reader comes back. Another device may have moved on in the meantime. */
    fun resumed(scope: CoroutineScope) {
        if (!active || pausedAt == 0L) return
        if (System.currentTimeMillis() - pausedAt < RECHECK_AFTER_MS) return
        scope.launch { pull(manual = false) }
    }

    /** The sync button: fetch the server's place, then go there or send this one. */
    fun syncNow(scope: CoroutineScope) {
        CrossPointSettings.ensureLoaded(context)
        if (!active) {
            tell("Sign in to CrossPoint sync in Settings first")
            return
        }
        scope.launch { pull(manual = true) }
    }

    fun accept() {
        val go = pendingGo
        _offer.value = null
        pendingGo = null
        settled = true
        if (go != null) {
            skipNext = true
            appScope.launch(Dispatchers.Main) {
                runCatching { go() }.onFailure {
                    skipNext = false
                    tell("Couldn't go to that place")
                }
            }
        }
    }

    /** Stays here. This device's place goes to the server, so the other device can follow. */
    fun decline() {
        _offer.value = null
        pendingGo = null
        settled = true
        appScope.launch { push(force = true) }
    }

    private suspend fun pull(manual: Boolean) = lock.withLock {
        val base = CrossPointSettings.baseUrl
        val user = CrossPointSettings.username
        val key = CrossPointSettings.key
        try {
            val document = document() ?: run {
                if (manual) tell("Couldn't read this book's file to match it")
                pullFailed = true
                return@withLock
            }
            val remote = KoSyncClient.fetch(base, user, key, document)
            pullFailed = false
            if (remote == null || remote.deviceId.equals(KoSyncClient.deviceId, ignoreCase = true)) {
                settled = true
                if (manual) {
                    if (push(force = true)) tell("Sent your place to the sync server")
                }
                return@withLock
            }
            when (val comparison = reader.compare(remote)) {
                is Comparison.Ahead -> {
                    val percent = (remote.percentage * 100).toInt()
                    if (CrossPointSettings.autoJump && !manual) {
                        settled = true
                        skipNext = true
                        withContext(Dispatchers.Main) { comparison.go() }
                        tell("Moved to $percent% from ${remote.device}")
                    } else {
                        pendingGo = comparison.go
                        _offer.value = SyncOffer(remote.device, percent, manual)
                    }
                }
                Comparison.Same -> {
                    settled = true
                    if (manual) tell("Already in step with ${remote.device}")
                }
                Comparison.Behind -> {
                    settled = true
                    if (manual && push(force = true)) tell("Sent your place. ${remote.device} was behind.")
                }
            }
        } catch (error: SyncException) {
            pullFailed = true
            ResultsLog.add("CrossPoint sync: ${error.message}")
            if (manual || error.signInProblem) tell(error.message ?: "Sync didn't work")
        }
    }

    /** Sends the latest place. Returns true when the server took it. */
    private suspend fun push(force: Boolean = false): Boolean {
        val current = spot ?: return false
        if (!active || !settled) return false
        if (!force && current == sent) return true
        val document = document() ?: return false
        return try {
            val titles = if (CrossPointSettings.sendTitles) {
                SyncTitles(fileNameOf(context, Uri.parse(book.documentUri)) ?: book.title, book.title, book.author)
            } else {
                null
            }
            KoSyncClient.push(
                CrossPointSettings.baseUrl,
                CrossPointSettings.username,
                CrossPointSettings.key,
                document,
                current.progress,
                current.percentage,
                titles,
            )
            sent = current
            true
        } catch (error: SyncException) {
            ResultsLog.add("CrossPoint sync: ${error.message}")
            if (force || error.signInProblem) tell(error.message ?: "Sync didn't work")
            false
        }
    }

    private companion object {
        const val PUSH_DELAY_MS = 20_000L
        const val RECHECK_AFTER_MS = 30_000L
    }
}
