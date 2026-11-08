package com.tim.reader.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.ResultsLog
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.InfoUpdate
import com.tim.reader.data.LibraryRepository
import com.tim.reader.library.Covers
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.InfoSource
import com.tim.reader.library.LookupResult
import com.tim.reader.library.MetadataLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Field(val label: String) {
    Title("Title"),
    Author("Author"),
    Description("Description"),
    Genres("Genres"),
    Publisher("Publisher"),
    Year("Year"),
    Cover("Cover"),
}

@Composable
private fun RemoteCover(url: String?, width: Int, height: Int) {
    val image by produceState<Bitmap?>(null, url) { value = url?.let { MetadataLookup.downloadImage(it, maxSide = 360) } }
    Box(
        Modifier
            .size(width.dp, height.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(EditorColors.Well),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        if (shown != null) {
            val picture = remember(shown) { shown.asImageBitmap() }
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ResultRow(result: LookupResult, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteCover(result.thumbUrl, 52, 78)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOf(result.author, result.year, result.publisher, result.extra).filter { it.isNotBlank() }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), color = EditorColors.Muted, fontSize = 13.sp, maxLines = 2)
            }
            if (result.description.isNotBlank()) {
                Text(result.description, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Finds a book or series online, then shows what would change so each part can be kept or
 * skipped. Nothing is sent until Search is tapped.
 */
@Composable
fun InfoLookupScreen(books: List<Book>, seriesTitle: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    val scope = rememberCoroutineScope()
    val first = books.first()
    val isSeries = seriesTitle != null
    val type: BookType = first.type
    var title by remember { mutableStateOf(seriesTitle ?: first.title) }
    var author by remember { mutableStateOf(first.author) }
    var source by remember {
        mutableStateOf(MetadataLookup.defaultSource(type, DeviceSettings.comicVineKey.isNotBlank()))
    }
    val results = remember { mutableStateListOf<LookupResult>() }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<LookupResult?>(null) }
    var applying by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var applyProblem by remember { mutableStateOf<String?>(null) }
    var pickJob by remember { mutableStateOf<Job?>(null) }
    // A series shares details, but its volumes keep their own titles and covers.
    val fields = if (isSeries) listOf(Field.Author, Field.Description, Field.Genres, Field.Publisher) else Field.entries
    val picked = remember { mutableStateMapOf<Field, Boolean>() }

    fun runSearch() {
        if (title.isBlank() || searching) return
        searching = true
        message = null
        results.clear()
        scope.launch {
            try {
                val found = MetadataLookup.search(
                    source,
                    title.trim(),
                    author.trim(),
                    type,
                    DeviceSettings.googleBooksKey,
                    DeviceSettings.comicVineKey,
                )
                results += found
                if (found.isEmpty()) message = "Nothing found. Try fewer words, or another source."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "Couldn't search ${source.label}: ${error.message ?: "no connection"}"
            } finally {
                searching = false
            }
        }
    }

    fun pick(result: LookupResult) {
        pickJob?.cancel()
        loading = true
        pickJob = scope.launch {
            val complete = MetadataLookup.complete(result)
            // Series records shouldn't rename a single volume or replace its own cover unless asked.
            val wholeSeries = result.source == InfoSource.AniList || result.source == InfoSource.ComicVine ||
                first.seriesId != null
            picked.clear()
            fields.forEach { field ->
                val has = when (field) {
                    Field.Title -> complete.title.isNotBlank()
                    Field.Author -> complete.author.isNotBlank()
                    Field.Description -> complete.description.isNotBlank()
                    Field.Genres -> complete.genres.isNotEmpty()
                    Field.Publisher -> complete.publisher.isNotBlank()
                    Field.Year -> complete.year.isNotBlank()
                    Field.Cover -> complete.coverUrl != null
                }
                picked[field] = has && !(wholeSeries && (field == Field.Title || field == Field.Cover))
            }
            chosen = complete
            loading = false
        }
    }

    BackHandler(enabled = chosen != null) { chosen = null }

    EditorTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(EditorColors.Panel)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (isSeries) "Find Series Info" else "Find Book Info", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            val result = chosen
            if (result == null) {
                EditorCard("Search") {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("Author") }, singleLine = true)
                    EditorListRow("Source", InfoSource.entries.map { it.label to it }, source, source.label) { option ->
                        if (option != null) source = option
                    }
                    EditorMainButton(if (searching) "Searching" else "Search", Modifier.fillMaxWidth(), enabled = !searching) {
                        runSearch()
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(EditorColors.Card, RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (searching || loading) CircularProgressIndicator(Modifier.padding(12.dp))
                    message?.let { Text(it, color = EditorColors.Muted, modifier = Modifier.padding(8.dp)) }
                    if (!searching && results.isEmpty() && message == null) {
                        Text(
                            "Searches only run when you tap Search. They send the title and author to ${source.label}.",
                            color = EditorColors.Muted,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(results, key = { it.source.name + it.id }) { found ->
                            ResultRow(found) { pick(found) }
                            EditorDivider()
                        }
                    }
                }
                EditorOutlineButton("Cancel", Modifier.fillMaxWidth(), onDone)
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    applyProblem?.let { Text(it, color = EditorColors.Warning) }
                    EditorCard(if (isSeries) "Apply to all ${books.size} volumes" else "Choose what to use") {
                        fields.forEachIndexed { index, field ->
                            if (index > 0) EditorDivider()
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(picked[field] == true, { picked[field] = it })
                                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                                    Text(field.label, fontWeight = FontWeight.Medium)
                                    when (field) {
                                        Field.Cover -> RemoteCover(result.coverUrl, 120, 180)
                                        else -> {
                                            val value = when (field) {
                                                Field.Title -> result.title
                                                Field.Author -> result.author
                                                Field.Description -> result.description
                                                Field.Genres -> result.genres.joinToString(", ")
                                                Field.Publisher -> result.publisher
                                                Field.Year -> result.year
                                                Field.Cover -> ""
                                            }
                                            Text(value.ifBlank { "Not in this result" }, color = EditorColors.Muted, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorOutlineButton("Back to results", Modifier.weight(1f)) { chosen = null }
                    EditorMainButton(if (applying) "Saving" else "Apply", Modifier.weight(1f), enabled = !applying) {
                        applying = true
                        val use = { field: Field -> picked[field] == true }
                        val update = InfoUpdate(
                            title = result.title.takeIf { use(Field.Title) },
                            author = result.author.takeIf { use(Field.Author) },
                            description = result.description.takeIf { use(Field.Description) },
                            genres = result.genres.takeIf { use(Field.Genres) },
                            publisher = result.publisher.takeIf { use(Field.Publisher) },
                            year = result.year.takeIf { use(Field.Year) },
                            source = result.source.label,
                        )
                        // This finishes even if the screen closes, so a series is never half updated.
                        appScope.launch {
                            val problem = try {
                                repository.applyInfo(books.map { it.id }, update)
                                val coverUrl = result.coverUrl
                                if (!isSeries && use(Field.Cover) && coverUrl != null) {
                                    val image = MetadataLookup.downloadImage(coverUrl, maxSide = 1200)
                                    if (image == null) {
                                        "The details were saved, but the cover couldn't be downloaded."
                                    } else {
                                        val path = withContext(Dispatchers.IO) { Covers.saveCustom(context, first.id, image) }
                                        Covers.deleteCustom(first.customCoverPath)
                                        repository.setCustomCover(first.id, path)
                                        null
                                    }
                                } else {
                                    null
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                ResultsLog.add("Saving book info failed: ${error::class.simpleName}: ${error.message}")
                                "Couldn't save: ${error.message ?: error::class.simpleName}"
                            }
                            withContext(Dispatchers.Main) {
                                if (problem == null) {
                                    onDone()
                                } else {
                                    applying = false
                                    applyProblem = problem
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
