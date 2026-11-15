package com.tim.reader.sync

import android.content.Context
import android.net.Uri
import com.tim.reader.ResultsLog
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.library.DeviceSettings
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
 * It sends nothing until it has seen the server's place and settled it: nothing saved there, this
 * device further on, or the reader choosing here or there. So reading offline, or leaving a
 * question unanswered, can't overwrite further progress made on another device.
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
    @Volatile private var pendingGo: (suspend () -> Unit)? = null

    /** True once this device may send its place. */
    @Volatile private var settled = false
    @Volatile private var lastPullAt = 0L
    /** The server turned down the sign-in, so asking again won't help until it changes. */
    @Volatile private var refusedKey: String? = null
    @Volatile private var spot: LocalSpot? = null
    @Volatile private var sent: LocalSpot? = null
    /** After a jump, the place it lands on came from the other device, so it isn't sent back. */
    @Volatile private var landing: LocalSpot? = null
    @Volatile private var landingUntil = 0L
    private var pushJob: Job? = null
    @Volatile private var pausedAt = 0L
    private val lock = Mutex()
    @Volatile private var documentId: Pair<Boolean, String>? = null

    init {
        CrossPointSettings.ensureLoaded(context)
        DeviceSettings.ensureLoaded(context)
    }

    private val active get() = CrossPointSettings.isReady && refusedKey != CrossPointSettings.key

    private suspend fun document(): String? {
        val byName = CrossPointSettings.matchByName
        documentId?.takeIf { it.first == byName }?.let { return it.second }
        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(book.documentUri)
            (if (byName) fileNameDocumentId(context, uri) else koreaderDocumentId(context, uri))
                ?.also { documentId = byName to it }
        }
    }

    /** Call once the book is open and its first page is showing. */
    fun start() {
        if (active) pullLater(manual = false)
    }

    private fun pullLater(manual: Boolean) {
        lastPullAt = System.currentTimeMillis()
        appScope.launch { pull(manual) }
    }

    /** Call whenever this device's place changes. */
    fun update(newSpot: LocalSpot) {
        val before = spot
        spot = newSpot
        if (landingUntil > 0) {
            if (System.currentTimeMillis() < landingUntil && newSpot != landing) {
                // The jump has landed. The other device already has this place.
                sent = newSpot
                landingUntil = 0
                return
            }
            if (System.currentTimeMillis() >= landingUntil) landingUntil = 0
        }
        if (!active) return
        if (!settled) {
            // The server's place hasn't been seen yet, perhaps because it couldn't be reached, so look again now and then.
            if (_offer.value == null && System.currentTimeMillis() - lastPullAt > RETRY_AFTER_MS) pullLater(manual = false)
            return
        }
        if (newSpot == sent || newSpot == before && pushJob?.isActive == true) return
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
        if (!active) return
        if (settled) {
            appScope.launch { push() }
        } else if (_offer.value == null) {
            // Still unsettled: one more look, which sends this place if the server's isn't further on.
            pullLater(manual = false)
        }
    }

    /** Call when the reader comes back. Another device may have moved on in the meantime. */
    fun resumed() {
        if (!active || pausedAt == 0L) return
        if (System.currentTimeMillis() - pausedAt < RECHECK_AFTER_MS) return
        if (_offer.value == null) pullLater(manual = false)
    }

    /** The sync button: fetch the server's place, then go there or send this one. */
    fun syncNow() {
        if (!CrossPointSettings.isReady) {
            tell("Sign in to CrossPoint sync in Settings first")
            return
        }
        refusedKey = null
        pullLater(manual = true)
    }

    fun accept() {
        val go = pendingGo
        _offer.value = null
        pendingGo = null
        settled = true
        if (go != null) jump(go)
    }

    /** Stays here. This device's place goes to the server, so the other device can follow. */
    fun decline() {
        _offer.value = null
        pendingGo = null
        settled = true
        appScope.launch { push(force = true) }
    }

    private fun jump(go: suspend () -> Unit) {
        landing = spot
        landingUntil = System.currentTimeMillis() + LANDING_MS
        appScope.launch(Dispatchers.Main) {
            runCatching { go() }.onFailure {
                landingUntil = 0
                tell("Couldn't go to that place")
            }
        }
    }

    private suspend fun pull(manual: Boolean) = lock.withLock {
        if (!CrossPointSettings.isReady) return@withLock
        val key = CrossPointSettings.key
        try {
            val document = document() ?: run {
                if (manual) tell("Couldn't read this book's file to match it")
                return@withLock
            }
            val remote = KoSyncClient.fetch(CrossPointSettings.baseUrl, CrossPointSettings.username, key, document)
            if (remote == null || remote.deviceId.equals(KoSyncClient.deviceId, ignoreCase = true)) {
                settled = true
                if (push(force = manual) && manual) tell("Sent your place to the sync server")
                return@withLock
            }
            when (val comparison = reader.compare(remote)) {
                is Comparison.Ahead -> {
                    val percent = (remote.percentage * 100).toInt()
                    if (CrossPointSettings.autoJump && !manual) {
                        settled = true
                        jump(comparison.go)
                        tell("Moved to $percent% from ${remote.device}")
                    } else {
                        // Nothing is sent while the question is open.
                        settled = false
                        pushJob?.cancel()
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
                    if (push(force = manual) && manual) tell("Sent your place. ${remote.device} was behind.")
                }
            }
        } catch (error: SyncException) {
            ResultsLog.add("CrossPoint sync: ${error.message}")
            if (error.signInProblem) {
                // Said once. Signing in again in Settings starts over.
                if (refusedKey != key || manual) tell(error.message ?: "Sync didn't work")
                refusedKey = key
            } else if (manual) {
                tell(error.message ?: "Sync didn't work")
            }
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
            if (error.signInProblem) {
                if (refusedKey != CrossPointSettings.key) tell(error.message ?: "Sync didn't work")
                refusedKey = CrossPointSettings.key
            } else if (force) {
                tell(error.message ?: "Sync didn't work")
            }
            false
        }
    }

    private companion object {
        const val PUSH_DELAY_MS = 20_000L
        const val RECHECK_AFTER_MS = 30_000L
        const val RETRY_AFTER_MS = 60_000L
        const val LANDING_MS = 5_000L
    }
}
