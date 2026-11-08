package com.tim.reader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import com.tim.reader.ResultsLog
import com.tim.reader.data.BookType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class InfoSource(val label: String) {
    OpenLibrary("Open Library"),
    GoogleBooks("Google Books"),
    AniList("AniList"),
    ComicVine("Comic Vine"),
}

/** One match from an online source. Anything the source didn't have is blank. */
data class LookupResult(
    val source: InfoSource,
    val id: String,
    val title: String,
    val author: String,
    val year: String,
    val description: String,
    val genres: List<String>,
    val publisher: String,
    val coverUrl: String?,
    val extra: String = "",
    /** A small version of the cover for lists. */
    val thumbUrl: String? = coverUrl,
)

private const val TIMEOUT_MS = 15_000
private const val RESULTS = 15
private const val USER_AGENT = "QuartzReader/1.0 (personal Android reading app; +https://github.com/riseordie-85/QuartzReader)"
private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

/**
 * Turns a description into plain text with paragraph breaks. Only HTML goes through the HTML
 * reader, because plain text would lose its line breaks there.
 */
fun plainDescription(text: String?): String {
    val raw = text.orEmpty().trim()
    if (raw.isEmpty()) return ""
    val flat = if ('<' in raw) {
        // AniList hides spoilers in spans, which would show once the tags are gone.
        val noSpoilers = raw.replace(Regex("<span class=['\"]markdown_spoiler['\"]>.*?</span>", RegexOption.DOT_MATCHES_ALL), "")
        Html.fromHtml(noSpoilers, Html.FROM_HTML_MODE_COMPACT).toString().replace("\uFFFC", "")
    } else {
        raw.replace("\r\n", "\n")
    }
    return flat.replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun plain(text: String?) = plainDescription(text)

private fun JSONObject.text(key: String) = if (has(key) && !isNull(key)) optString(key).trim() else ""

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { optString(it).trim() }.filter { it.isNotEmpty() }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

/**
 * Looks books up online. Nothing here runs unless the user asks, and nothing is sent except the
 * search words and, for sources that need one, the user's own key.
 */
object MetadataLookup {

    /** The best place to start for each kind of book. */
    fun defaultSource(type: BookType, hasComicVineKey: Boolean) = when (type) {
        BookType.Book -> InfoSource.OpenLibrary
        BookType.Comic -> if (hasComicVineKey) InfoSource.ComicVine else InfoSource.GoogleBooks
        BookType.Manga, BookType.Manhwa -> InfoSource.AniList
    }

    private fun request(url: String, body: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw IOException("${url.substringBefore('?')} answered $code: ${text.take(200)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** Searches one source. Throws with a readable message when the source can't be reached. */
    suspend fun search(
        source: InfoSource,
        title: String,
        author: String,
        type: BookType,
        googleKey: String,
        comicVineKey: String,
    ): List<LookupResult> = withContext(Dispatchers.IO) {
        try {
            when (source) {
                InfoSource.OpenLibrary -> openLibrary(title, author)
                InfoSource.GoogleBooks -> googleBooks(title, author, googleKey)
                InfoSource.AniList -> aniList(title, type)
                InfoSource.ComicVine -> comicVine(title, comicVineKey)
            }
        } catch (error: Exception) {
            ResultsLog.add("Lookup on ${source.label} failed: ${error::class.simpleName}: ${error.message}")
            throw error
        }
    }

    /** Fills in what a search result leaves out. Only Open Library keeps descriptions apart. */
    suspend fun complete(result: LookupResult): LookupResult = withContext(Dispatchers.IO) {
        if (result.source != InfoSource.OpenLibrary || result.description.isNotEmpty()) return@withContext result
        runCatching {
            val work = JSONObject(request("https://openlibrary.org${result.id}.json"))
            val description = work.optJSONObject("description")?.text("value") ?: work.text("description")
            result.copy(description = plainDescription(description))
        }.getOrElse { error ->
            ResultsLog.add("Open Library description failed: ${error.message}")
            result
        }
    }

    /** Downloads an image, shrunk on decode so its longer side is about [maxSide] pixels. */
    suspend fun downloadImage(url: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            val bytes = try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.instanceFollowRedirects = true
                connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        if (buffer.size() > MAX_IMAGE_BYTES) throw IOException("Image is too large")
                    }
                    buffer.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrElse { error ->
            if (error is OutOfMemoryError || error is Exception) null else throw error
        }
    }

    private fun openLibrary(title: String, author: String): List<LookupResult> {
        val fields = "key,title,author_name,first_publish_year,cover_i,publisher,subject"
        var url = "https://openlibrary.org/search.json?title=${encode(title)}&limit=$RESULTS&fields=$fields"
        if (author.isNotBlank()) url += "&author=${encode(author)}"
        val docs = JSONObject(request(url)).optJSONArray("docs").objects()
        return docs.map { doc ->
            val cover = doc.optLong("cover_i", 0L).takeIf { it > 0 }
            LookupResult(
                source = InfoSource.OpenLibrary,
                id = doc.text("key"),
                title = doc.text("title"),
                author = doc.optJSONArray("author_name").strings().take(3).joinToString(", "),
                year = doc.optInt("first_publish_year", 0).takeIf { it > 0 }?.toString().orEmpty(),
                description = "",
                genres = doc.optJSONArray("subject").strings().take(8),
                publisher = doc.optJSONArray("publisher").strings().firstOrNull().orEmpty(),
                coverUrl = cover?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                thumbUrl = cover?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
            )
        }.distinctBy { it.id }
    }

    private fun googleBooks(title: String, author: String, key: String): List<LookupResult> {
        var query = "intitle:$title"
        if (author.isNotBlank()) query += " inauthor:$author"
        var url = "https://www.googleapis.com/books/v1/volumes?q=${encode(query)}&maxResults=$RESULTS&printType=books"
        if (key.isNotBlank()) url += "&key=${encode(key)}"
        val items = JSONObject(request(url)).optJSONArray("items").objects()
        return items.mapNotNull { item ->
            val info = item.optJSONObject("volumeInfo") ?: return@mapNotNull null
            val images = info.optJSONObject("imageLinks")
            val image = images?.let { it.text("thumbnail").ifEmpty { it.text("smallThumbnail") } }
            val small = images?.text("smallThumbnail")?.ifEmpty { null } ?: image
            val subtitle = info.text("subtitle")
            LookupResult(
                source = InfoSource.GoogleBooks,
                id = item.text("id"),
                title = info.text("title") + if (subtitle.isNotEmpty()) ": $subtitle" else "",
                author = info.optJSONArray("authors").strings().joinToString(", "),
                year = info.text("publishedDate").take(4),
                description = plain(info.text("description")),
                genres = info.optJSONArray("categories").strings(),
                publisher = info.text("publisher"),
                // Google's links are plain http and add a page curl unless asked not to.
                coverUrl = image?.takeIf { it.isNotEmpty() }
                    ?.replace("http://", "https://")
                    ?.replace("&edge=curl", ""),
                thumbUrl = small?.takeIf { it.isNotEmpty() }
                    ?.replace("http://", "https://")
                    ?.replace("&edge=curl", ""),
            )
        }.distinctBy { it.id }
    }

    private const val ANILIST_QUERY = """
        query (${'$'}search: String, ${'$'}formats: [MediaFormat]) {
          Page(perPage: 15) {
            media(search: ${'$'}search, type: MANGA, format_in: ${'$'}formats) {
              id
              title { romaji english }
              description(asHtml: true)
              genres
              format
              volumes
              countryOfOrigin
              startDate { year }
              coverImage { extraLarge large medium }
              staff(perPage: 4) { edges { role node { name { full } } } }
            }
          }
        }
    """

    private fun aniList(title: String, type: BookType): List<LookupResult> {
        // Light novels are filed as books, so a book search looks for novels.
        val formats = if (type == BookType.Book) listOf("NOVEL") else listOf("MANGA", "ONE_SHOT")
        val body = JSONObject()
            .put("query", ANILIST_QUERY)
            .put("variables", JSONObject().put("search", title).put("formats", JSONArray(formats)))
        val media = JSONObject(request("https://graphql.anilist.co", body.toString()))
            .optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media").objects()
        return media.map { item ->
            val names = item.optJSONObject("title")
            val creators = item.optJSONObject("staff")?.optJSONArray("edges").objects()
                .filter { edge -> edge.text("role").let { "Story" in it || "Art" in it || "Original" in it } }
                .mapNotNull { it.optJSONObject("node")?.optJSONObject("name")?.text("full")?.takeIf { name -> name.isNotEmpty() } }
                .distinct()
            val volumes = item.optInt("volumes", 0)
            val country = item.text("countryOfOrigin")
            LookupResult(
                source = InfoSource.AniList,
                id = item.optInt("id").toString(),
                title = names?.text("english")?.ifEmpty { null } ?: names?.text("romaji").orEmpty(),
                author = creators.joinToString(", "),
                year = item.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }?.toString().orEmpty(),
                description = plain(item.text("description")),
                genres = item.optJSONArray("genres").strings(),
                publisher = "",
                coverUrl = item.optJSONObject("coverImage")?.let { it.text("extraLarge").ifEmpty { it.text("large") } }
                    ?.takeIf { it.isNotEmpty() },
                thumbUrl = item.optJSONObject("coverImage")?.let { it.text("medium").ifEmpty { it.text("large") } }
                    ?.takeIf { it.isNotEmpty() },
                extra = listOfNotNull(
                    if (country == "KR") "Manhwa" else null,
                    if (volumes > 0) "$volumes volumes" else null,
                ).joinToString(", "),
            )
        }.distinctBy { it.id }
    }

    private fun comicVine(title: String, key: String): List<LookupResult> {
        if (key.isBlank()) throw IOException("Comic Vine needs your API key, which you can add in Settings")
        val fields = "id,name,start_year,publisher,deck,description,image,count_of_issues"
        // Comic Vine's search returns at most ten.
        val url = "https://comicvine.gamespot.com/api/search/?api_key=${encode(key)}&format=json" +
            "&resources=volume&limit=10&field_list=$fields&query=${encode(title)}"
        val root = JSONObject(request(url))
        if (root.optInt("status_code", 1) != 1) throw IOException("Comic Vine: ${root.text("error")}")
        return root.optJSONArray("results").objects().map { item ->
            val issues = item.optInt("count_of_issues", 0)
            LookupResult(
                source = InfoSource.ComicVine,
                id = item.optInt("id").toString(),
                title = item.text("name"),
                author = "",
                year = item.text("start_year"),
                description = plain(item.text("description")).ifEmpty { item.text("deck") },
                genres = emptyList(),
                publisher = item.optJSONObject("publisher")?.text("name").orEmpty(),
                coverUrl = item.optJSONObject("image")?.let { it.text("original_url").ifEmpty { it.text("medium_url") } }
                    ?.takeIf { it.isNotEmpty() },
                thumbUrl = item.optJSONObject("image")?.let { it.text("small_url").ifEmpty { it.text("medium_url") } }
                    ?.takeIf { it.isNotEmpty() },
                extra = if (issues > 0) "$issues issues" else "",
            )
        }.distinctBy { it.id }
    }
}
