package com.tim.reader.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.ViewMode
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.LibraryFolder
import com.tim.reader.library.LibraryOrganizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onHomeStyle: () -> Unit,
    onTestTools: () -> Unit,
    onFindExisting: () -> Unit = {},
    onStats: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
    var organizing by remember { mutableStateOf(false) }
    var choosingRow by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var googleKey by remember { mutableStateOf(DeviceSettings.googleBooksKey) }
    var comicVineKey by remember { mutableStateOf(DeviceSettings.comicVineKey) }
    if (showLicenses) {
        val text = remember {
            runCatching { context.assets.open("font-licenses.txt").use { it.readBytes().decodeToString() } }.getOrDefault("")
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Font licenses") },
            text = { Text(text, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ showLicenses = false }) { Text("Close") } },
        )
    }
    val homeRowKey = booksPerRowKey("home", ViewMode.Shelf)
    val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat()
    val homeWidth = if (screenWidth >= 700f && DeviceSettings.splitView) screenWidth / 2 else screenWidth
    if (choosingRow) BooksPerRowDialog(homeRowKey, columnsFor(homeWidth)) { choosingRow = false }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Library folder", style = MaterialTheme.typography.titleMedium)
        Text(LibraryFolder.get(context).displayPath)
        OutlinedButton(onFindExisting, Modifier.fillMaxWidth()) { Text("Add books already in this folder") }
        OutlinedButton(
            onClick = {
                organizing = true
                appScope.launch {
                    val (joined, moved) = LibraryOrganizer(context).organizeAll()
                    withContext(Dispatchers.Main) {
                        organizing = false
                        Toast.makeText(context, "Added $joined books to series and moved $moved into place", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !organizing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (organizing) "Organizing" else "Organize library folder") }
        Text("Backup and sync", style = MaterialTheme.typography.titleMedium)
        SyncSetting()
        Text("Reading", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onStats, Modifier.fillMaxWidth()) { Text("Reading stats") }
        ReminderSetting()
        GoodreadsImportButton()
        Text(
            "On Goodreads, go to My Books, then Import and export, and choose Export Library. Pick the file it makes here.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Home", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onHomeStyle, Modifier.fillMaxWidth()) { Text("Edit Home style") }
        OutlinedButton({ choosingRow = true }, Modifier.fillMaxWidth()) { Text("Books per row on Home") }
        Text("Display", style = MaterialTheme.typography.titleMedium)
        ToggleRow("Split view on large screens", DeviceSettings.splitView) { DeviceSettings.updateSplitView(it) }
        Text("Book info lookups", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open Library and AniList need nothing. Google Books works without a key but allows more with one. " +
                "Comic Vine needs a free key from comicvine.gamespot.com/api.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            googleKey,
            { googleKey = it },
            Modifier.fillMaxWidth(),
            label = { Text("Google Books key (optional)") },
            singleLine = true,
        )
        OutlinedTextField(comicVineKey, { comicVineKey = it }, Modifier.fillMaxWidth(), label = { Text("Comic Vine key") }, singleLine = true)
        OutlinedButton(
            { DeviceSettings.updateLookupKeys(googleKey, comicVineKey) },
            Modifier.fillMaxWidth(),
            enabled = googleKey.trim() != DeviceSettings.googleBooksKey || comicVineKey.trim() != DeviceSettings.comicVineKey,
        ) { Text("Save keys") }
        Text("About", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ showLicenses = true }, Modifier.fillMaxWidth()) { Text("Font licenses") }
        Text("Testing", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onTestTools, Modifier.fillMaxWidth()) { Text("Test tools") }
    }
}
