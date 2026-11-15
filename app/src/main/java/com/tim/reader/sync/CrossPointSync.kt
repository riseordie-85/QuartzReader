package com.tim.reader.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.tim.reader.library.DeviceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Progress sync with CrossPoint and KOReader devices. They share a small protocol, KOReader's
 * "kosync": each book is named by a code made from its file, and the server keeps one place per
 * book per account. Nothing here leaves the device unless the reader turns it on.
 */
object CrossPointSettings {
    const val DEFAULT_SERVER = "https://sync.crosspointreader.com"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server"
    private const val KEY_USERNAME = "username"
    private const val KEY_KEY = "key"
    private const val KEY_SEND_TITLES = "send_titles"
    private const val KEY_AUTO_JUMP = "auto_jump"
    private const val KEY_BY_NAME = "by_name"
    private var preferences: SharedPreferences? = null

    var enabled by mutableStateOf(false)
        private set

    /** As typed. Empty means the CrossPoint server. */
    var server by mutableStateOf("")
        private set

    var username by mutableStateOf("")
        private set

    /** The password's MD5, which is what these servers take. The password itself isn't kept. */
    var key by mutableStateOf("")
        private set

    /** Sends the title, author and file name along with progress. Off sends only the book's code. */
    var sendTitles by mutableStateOf(false)
        private set

    /** Goes to a place further ahead from another device without asking first. */
    var autoJump by mutableStateOf(false)
        private set

    /** Matches books by file name instead of by their contents, like KOReader's option. */
    var matchByName by mutableStateOf(false)
        private set

    val isSignedIn get() = username.isNotBlank() && key.isNotBlank()
    val isReady get() = enabled && isSignedIn
    val baseUrl get() = normalizeServer(server)

    fun ensureLoaded(context: Context) {
        if (preferences != null) return
        val saved = context.applicationContext.getSharedPreferences("crosspoint", Context.MODE_PRIVATE)
        preferences = saved
        enabled = saved.getBoolean(KEY_ENABLED, false)
        server = saved.getString(KEY_SERVER, null).orEmpty()
        username = saved.getString(KEY_USERNAME, null).orEmpty()
        key = saved.getString(KEY_KEY, null).orEmpty()
        sendTitles = saved.getBoolean(KEY_SEND_TITLES, false)
        autoJump = saved.getBoolean(KEY_AUTO_JUMP, false)
        matchByName = saved.getBoolean(KEY_BY_NAME, false)
    }

    fun updateEnabled(on: Boolean) {
        enabled = on
        preferences?.edit { putBoolean(KEY_ENABLED, on) }
    }

    fun signIn(server: String, username: String, key: String) {
        this.server = server.trim()
        this.username = username.trim()
        this.key = key
        preferences?.edit {
            putString(KEY_SERVER, this@CrossPointSettings.server)
            putString(KEY_USERNAME, this@CrossPointSettings.username)
            putString(KEY_KEY, key)
        }
    }

    fun signOut() {
        key = ""
        preferences?.edit { remove(KEY_KEY) }
    }

    fun updateSendTitles(on: Boolean) {
        sendTitles = on
        preferences?.edit { putBoolean(KEY_SEND_TITLES, on) }
    }

    fun updateAutoJump(on: Boolean) {
        autoJump = on
        preferences?.edit { putBoolean(KEY_AUTO_JUMP, on) }
    }

    fun updateMatchByName(on: Boolean) {
        matchByName = on
        preferences?.edit { putBoolean(KEY_BY_NAME, on) }
    }
}

/** The server address with a scheme and without a trailing slash. Empty means CrossPoint's. */
fun normalizeServer(typed: String): String {
    var url = typed.trim().trimEnd('/')
    if (url.isEmpty()) return CrossPointSettings.DEFAULT_SERVER
    if (!url.contains("://")) url = "https://$url"
    return url
}

fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

/** A place in a book as another device saved it. */
data class RemoteProgress(
    /** A KOReader path for text, or a page number from 1 for pages. */
    val progress: String,
    /** From 0 to 1. */
    val percentage: Double,
    val device: String,
    val deviceId: String,
    /** Seconds since 1970. */
    val timestamp: Long,
)

/** The book's name and author, sent only when the reader allows it. */
data class SyncTitles(val fileName: String, val title: String, val author: String)

class SyncException(message: String, val signInProblem: Boolean = false) : IOException(message)

/** Talks to a kosync server. Every call throws [SyncException] with a message to show. */
object KoSyncClient {
    private const val ACCEPT = "application/vnd.koreader.v1+json"
    private const val TIMEOUT_MS = 15_000

    /** How this device appears on the other devices' screens. */
    val deviceName get() = "Quartz Reader (${Build.MODEL})"

    /** KOReader's device ids are 32 hex digits, so this one is too. */
    val deviceId get() = DeviceSettings.deviceId.replace("-", "").uppercase().padEnd(32, '0').take(32)

    private class Reply(val code: Int, val text: String)

    private fun call(
        base: String,
        path: String,
        method: String,
        username: String?,
        key: String?,
        body: JSONObject? = null,
    ): Reply {
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw SyncException("That server address doesn't look right")
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", ACCEPT)
            if (username != null && key != null) {
                connection.setRequestProperty("x-auth-user", username)
                connection.setRequestProperty("x-auth-key", key)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            return Reply(code, text)
        } catch (error: SyncException) {
            throw error
        } catch (error: Exception) {
            throw SyncException("Couldn't reach the sync server")
        } finally {
            connection.disconnect()
        }
    }

    private fun failure(reply: Reply): SyncException = when (reply.code) {
        401, 403 -> SyncException("The server didn't accept that username and password", signInProblem = true)
        else -> SyncException("The sync server answered ${reply.code}")
    }

    /** Checks a username and password key. */
    suspend fun authenticate(base: String, username: String, key: String) = withContext(Dispatchers.IO) {
        val reply = call(base, "/users/auth", "GET", username, key)
        if (reply.code !in 200..299) throw failure(reply)
    }

    /** Makes an account. The server only ever sees the password's MD5. */
    suspend fun register(base: String, username: String, key: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", key)
        val reply = call(base, "/users/create", "POST", null, null, body)
        when {
            reply.code in 200..299 -> Unit
            reply.code == 402 -> throw SyncException("That username is taken on this server. If it's yours, sign in instead.")
            reply.code == 403 -> throw SyncException("This server isn't taking new accounts")
            else -> throw failure(reply)
        }
    }

    /** The place saved for a book, or null when no device has saved one. */
    suspend fun fetch(base: String, username: String, key: String, document: String): RemoteProgress? =
        withContext(Dispatchers.IO) {
            val reply = call(base, "/syncs/progress/" + URLEncoder.encode(document, "UTF-8"), "GET", username, key)
            if (reply.code == 404 || reply.code == 204) return@withContext null
            if (reply.code !in 200..299) throw failure(reply)
            val json = runCatching { JSONObject(reply.text) }.getOrNull() ?: return@withContext null
            // An empty answer means nothing's saved. Pages arrive as numbers, text places as strings.
            val progress = json.opt("progress")?.takeIf { it != JSONObject.NULL }?.let { value ->
                if (value is Number && value.toDouble() == Math.floor(value.toDouble())) value.toLong().toString() else value.toString()
            }
            if (progress.isNullOrBlank()) return@withContext null
            RemoteProgress(
                progress = progress,
                percentage = json.optDouble("percentage", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                device = json.optString("device").ifBlank { "another device" },
                deviceId = json.optString("device_id"),
                timestamp = json.optLong("timestamp", 0L),
            )
        }

    /** Saves this device's place in a book. */
    suspend fun push(
        base: String,
        username: String,
        key: String,
        document: String,
        progress: String,
        percentage: Double,
        titles: SyncTitles?,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("document", document)
            .put("progress", progress)
            .put("percentage", percentage.coerceIn(0.0, 1.0))
            .put("device", deviceName)
            .put("device_id", deviceId)
        if (titles != null) {
            body.put(
                "metadata",
                JSONObject().put("filename", titles.fileName).put("title", titles.title).put("authors", titles.author),
            )
        }
        val reply = call(base, "/syncs/progress", "PUT", username, key, body)
        if (reply.code !in 200..299) throw failure(reply)
    }
}

/**
 * The code KOReader and CrossPoint name a book by: an MD5 of 1 KB samples from the start of the file
 * and at 1 KB, 4 KB, 16 KB and so on, each four times further in, up to 1 GB.
 */
fun koreaderDocumentId(context: Context, uri: Uri): String? = runCatching {
    val digest = MessageDigest.getInstance("MD5")
    val offsets = (-1..10).map { i -> if (i < 0) 0L else 1024L shl (2 * i) }
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
    // The stream owns the descriptor and closes it once.
    val read = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
        stream.channel.let { channel ->
            val size = channel.size()
            for (offset in offsets) {
                if (offset >= size) break
                val buffer = ByteBuffer.allocate(minOf(1024L, size - offset).toInt())
                var at = offset
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer, at)
                    if (count <= 0) break
                    at += count
                }
                digest.update(buffer.array(), 0, buffer.position())
            }
            true
        }
    }
    if (read != true) return@runCatching null
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

/** The other way KOReader can name a book: an MD5 of its file name. */
fun fileNameDocumentId(context: Context, uri: Uri): String? = fileNameOf(context, uri)?.let { md5Hex(it.toByteArray()) }

fun fileNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
        if (rows.moveToFirst()) rows.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
