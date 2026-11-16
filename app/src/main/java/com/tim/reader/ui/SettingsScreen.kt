package com.tim.reader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.ViewMode
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.LibraryFolder
import com.tim.reader.library.LibraryOrganizer
import com.tim.reader.theme.BookFonts
import com.tim.reader.theme.Presets
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemeFiles
import com.tim.reader.theme.ThemeJson
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.fontFamily
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsTab(val label: String) {
    Sync("Sync & Backup"),
    Metadata("Metadata"),
    Reading("Reading"),
    Themes("Themes"),
    Fonts("Fonts"),
    About("About"),
}

@Composable
fun SettingsScreen(
    onHomeStyle: () -> Unit,
    onTestTools: () -> Unit,
    onFindExisting: () -> Unit = {},
    onStats: () -> Unit = {},
    onGoals: () -> Unit = {},
) {
    val repository = LibraryRepository.get(LocalContext.current)
    val state by repository.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SettingsTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTitleBar(SettingsTab.entries[tab].label)
                SettingsTabs(SettingsTab.entries.map { it.label }, tab) { tab = it }
            }
            Column(
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (SettingsTab.entries[tab]) {
                    SettingsTab.Sync -> SyncTab(onFindExisting)
                    SettingsTab.Metadata -> MetadataTab()
                    SettingsTab.Reading -> ReadingTab(onStats, onGoals)
                    SettingsTab.Themes -> ThemesTab(state, onHomeStyle)
                    SettingsTab.Fonts -> FontsTab()
                    SettingsTab.About -> AboutTab(onTestTools)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SyncTab(onFindExisting: () -> Unit) {
    val context = LocalContext.current
    SettingsCard("Library folder", "Where your books live, sorted into Books, Comics, Manga and Manhwa.") {
        Text(LibraryFolder.get(context).displayPath, fontWeight = FontWeight.Bold)
        SettingsButton("Add books already in this folder", onClick = onFindExisting)
        FolderWatchSettings()
    }
    SettingsCard("Backup and sync", "Your reading data is kept as a small file in the library folder, so it's backed up with your books.") {
        SyncSetting()
    }
    SettingsCard("CrossPoint and KOReader") { CrossPointSetting() }
    SettingsCard("Bring in reading history") {
        GoodreadsImportButton()
        SettingsHint("On Goodreads, go to My Books, then Import and export, and choose Export Library. Pick the file it makes here.")
        SettingsDivider()
        EreaderImportButton()
        SettingsHint("Brings in reading time and finished books from an Xteink running QuartzInk.")
    }
}

@Composable
private fun MetadataTab() {
    val context = LocalContext.current
    var organizing by remember { mutableStateOf(false) }
    var googleKey by remember { mutableStateOf(DeviceSettings.googleBooksKey) }
    var comicVineKey by remember { mutableStateOf(DeviceSettings.comicVineKey) }
    SettingsCard(
        "Book info lookups",
        "Titles, authors, series and covers come from the file first. Looking a book up fills in the rest. " +
            "Open Library and AniList need nothing. Google Books works without a key but allows more with one. " +
            "Comic Vine needs a free key from comicvine.gamespot.com/api.",
    ) {
        OutlinedTextField(googleKey, { googleKey = it }, Modifier.fillMaxWidth(), label = { Text("Google Books key (optional)") }, singleLine = true)
        OutlinedTextField(comicVineKey, { comicVineKey = it }, Modifier.fillMaxWidth(), label = { Text("Comic Vine key") }, singleLine = true)
        SettingsButton(
            "Save keys",
            primary = true,
            enabled = googleKey.trim() != DeviceSettings.googleBooksKey || comicVineKey.trim() != DeviceSettings.comicVineKey,
        ) { DeviceSettings.updateLookupKeys(googleKey, comicVineKey) }
    }
    SettingsCard(
        "Series and folders",
        "Groups loose books into series by their file names, like v03, c001, Chapter 45 or 第01巻, then moves every book into its Type and Series folder.",
    ) {
        SettingsButton(if (organizing) "Organizing" else "Organize library folder", enabled = !organizing) {
            organizing = true
            appScope.launch {
                val (joined, moved) = LibraryOrganizer(context).organizeAll()
                withContext(Dispatchers.Main) {
                    organizing = false
                    Toast.makeText(context, "Added $joined books to series and moved $moved into place", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun ReadingTab(onStats: () -> Unit, onGoals: () -> Unit) {
    SettingsCard("Stats and goals") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsButton("Reading stats", Modifier.weight(1f), onClick = onStats)
            SettingsButton("Goals", Modifier.weight(1f), onClick = onGoals)
        }
        ReminderSetting()
        SettingsDivider()
        SettingsSwitch(
            "Weekday chart starts on Sunday",
            "For Home's finished-by-weekday chart. Off starts it on Monday.",
            DeviceSettings.chartStartsSunday,
        ) { DeviceSettings.updateChartStartsSunday(it) }
    }
    SettingsCard("While reading") {
        SettingsSwitch(
            "Show time left",
            "How long the chapter and book will take at your pace, in the corner of the page.",
            DeviceSettings.showTimeLeft,
        ) { DeviceSettings.updateShowTimeLeft(it) }
        SettingsDivider()
        SettingsSwitch("Keep the screen on", "Stops the screen dimming while a book is open.", DeviceSettings.keepScreenOn) {
            DeviceSettings.updateKeepScreenOn(it)
        }
    }
    SettingsCard("Large screens") {
        SettingsSwitch(
            "Split view",
            "On a tablet or an unfolded phone, shows the shelf on one side and the book's details on the other.",
            DeviceSettings.splitView,
        ) { DeviceSettings.updateSplitView(it) }
    }
}

@Composable
private fun ThemesTab(state: LibraryState, onHomeStyle: () -> Unit) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
    var choosingRow by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    // By name, so a style being exported survives the screen being rebuilt while the file picker is open.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }

    val homeRowKey = booksPerRowKey("home", ViewMode.Shelf)
    val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat()
    val homeWidth = if (screenWidth >= 700f && DeviceSettings.splitView) screenWidth / 2 else screenWidth
    if (choosingRow) BooksPerRowDialog(homeRowKey, columnsFor(homeWidth)) { choosingRow = false }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val theme = state.userPresets.firstOrNull { it.first == exporting }?.second
        exporting = null
        if (uri != null && theme != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        (context.contentResolver.openOutputStream(uri) ?: error("No output")).use { ThemeFiles.exportStyle(theme, it) }
                    }.isSuccess
                }
                Toast.makeText(context, if (saved) "Style exported" else "Couldn't export the style", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val (name, theme) = withContext(Dispatchers.IO) {
                    val text = runCatching { context.contentResolver.openInputStream(uri)?.use { ThemeFiles.importStyle(it) } }.getOrNull()
                    val label = runCatching {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                    }.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported style"
                    label to text?.let { runCatching { ThemeJson.parse(it) }.getOrNull() }
                }
                if (theme == null) {
                    Toast.makeText(context, "That file isn't a Quartz Reader style", Toast.LENGTH_SHORT).show()
                } else {
                    repository.saveUserPreset(name, theme)
                    Toast.makeText(context, "Added $name to your styles", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    SettingsCard("Home") {
        SettingsButton("Edit Home style", onClick = onHomeStyle)
        SettingsButton("Books per row on Home") { choosingRow = true }
    }

    val current = state.homeTheme ?: Presets.default
    val styles = Presets.all + state.userPresets
    SettingsCard("Default Home style", "Home uses this style. Shelves with a style of their own keep it.") {
        styles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (name, theme) ->
                    HomeStyleTile(name, theme, selected = theme == current, Modifier.weight(1f)) {
                        scope.launch { repository.setHomeTheme(theme) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    SettingsCard("Your styles", "Styles you save in the style editor, or bring in from a file. Exporting one keeps its pictures and fonts with it.") {
        if (state.userPresets.isEmpty()) SettingsHint("None yet.")
        state.userPresets.forEachIndexed { index, (name, theme) ->
            if (index > 0) SettingsDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.weight(1f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SettingsButton("Export", Modifier) {
                    exporting = name
                    export.launch("$name.zip")
                }
                SettingsButton("Remove", Modifier) { removing = name }
            }
        }
        SettingsButton("Import a style", primary = true) { import.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) }
    }

    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $name?") },
            text = { Text("Shelves already using it keep their look.") },
            confirmButton = {
                TextButton({
                    removing = null
                    scope.launch { repository.removeUserPreset(name) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

/** A style shown as its header bar, with its name in the header's text color. */
@Composable
private fun HomeStyleTile(name: String, theme: ShelfTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.Black else SettingsColors.Line, shape)
            .padding(6.dp)
            .clickable(role = Role.RadioButton, onClickLabel = "Use $name on Home", onClick = onClick),
    ) {
        val bar = cornerShape(theme.header.cornerDp.coerceAtMost(24f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .themedFill(theme.background, RoundedCornerShape(10.dp))
                .padding(6.dp)
                .themedFill(theme.header.fill, bar)
                .themedStroke(theme.header.stroke, bar),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name,
                color = Color(theme.header.textColor),
                fontFamily = fontFamily(theme.title.font),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun FontsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val mine = remember(version) { ThemeFiles.fonts() }
    var removing by remember { mutableStateOf<String?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { runCatching { ThemeFiles.importFont(context, uri) }.getOrNull() }
                if (name == null) {
                    Toast.makeText(context, "Android can't use that font file", Toast.LENGTH_SHORT).show()
                } else {
                    version++
                    Toast.makeText(context, "Added ${ThemeFiles.fontLabel(name)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    SettingsCard("Your fonts", "Fonts you add can be used in styles, for headers, shelf titles and buttons. A copy is kept in the library folder.") {
        if (mine.isEmpty()) SettingsHint("None yet. TTF and OTF files work.")
        mine.forEachIndexed { index, font ->
            if (index > 0) SettingsDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ThemeFiles.fontLabel(font),
                    Modifier.weight(1f),
                    fontFamily = ThemeFiles.fontFamily(font),
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingsButton("Remove", Modifier) { removing = font }
            }
        }
        SettingsButton("Import a font", primary = true) {
            import.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
        }
    }
    SettingsCard("Built-in fonts", "These come with the app, for books and styles alike.") {
        BookFonts.forThemes.forEach { font ->
            Text(
                font.name,
                fontFamily = BookFonts.family(BookFonts.themeName(font)) ?: fontFamily("sans"),
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    removing?.let { font ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${ThemeFiles.fontLabel(font)}?") },
            text = { Text("Styles using it switch to the plain font.") },
            confirmButton = {
                TextButton({
                    removing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { ThemeFiles.deleteFont(font) }
                        version++
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutTab(onTestTools: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "No browser to open that", Toast.LENGTH_SHORT).show() }
    }
    SettingsCard("Quartz Reader", "A reader for books, comics, manga and manhwa.") {
        if (version.isNotBlank()) Text("Version $version", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsButton("Quartz site", Modifier.weight(1f)) { open("https://quartz.timcordova.com") }
            SettingsButton("GitHub", Modifier.weight(1f)) { open("https://github.com/riseordie-85/QuartzReader") }
        }
    }
    SettingsCard(
        "Privacy",
        "No ads, no tracking and no accounts of ours. Quartz Reader only goes online when you look up a book's details, " +
            "or to sync with a folder or server you set up.",
    ) {}
    SettingsCard("Licenses") {
        SettingsButton("Font licenses") { showLicenses = true }
    }
    SettingsCard("Testing", "Tools for checking the app on a new device.") {
        SettingsButton("Test tools", onClick = onTestTools)
    }
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
}
