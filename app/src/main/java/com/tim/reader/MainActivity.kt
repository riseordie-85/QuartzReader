package com.tim.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.engine.PageSource
import com.tim.reader.ui.ImageReaderScreen
import com.tim.reader.ui.ShelfTestScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ResultsLog.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Shelf : Screen
    data object Results : Screen
    class Reader(val source: PageSource) : Screen
}

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun show(opened: Opened) {
        busy = false
        when (opened) {
            is Opened.Images -> screen = Screen.Reader(opened.source)
            Opened.Epub -> context.startActivity(Intent(context, EpubActivity::class.java))
            is Opened.Message -> message = opened.text
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch { show(openFile(context, uri)) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch { show(openFolder(context, uri)) }
        }
    }
    val pickSpeedFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch {
                runFolderSpeedTest(context, uri)
                busy = false
                screen = Screen.Results
            }
        }
    }

    val current = screen
    BackHandler(enabled = current !is Screen.Home) {
        // Closing can wait on a page that's still loading, so it stays off the main thread.
        if (current is Screen.Reader) {
            val closing = current.source
            scope.launch(Dispatchers.IO + NonCancellable) { closing.close() }
        }
        screen = Screen.Home
    }

    when (current) {
        Screen.Home -> Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Quartz Reader", style = MaterialTheme.typography.headlineMedium)
            Text("Phase 0 test app", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button({ pickFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Open a test file") }
            Button({ pickFolder.launch(null) }, Modifier.fillMaxWidth()) { Text("Open a folder of images") }
            Button({ screen = Screen.Shelf }, Modifier.fillMaxWidth()) { Text("Shelf test") }
            Button({ pickSpeedFolder.launch(null) }, Modifier.fillMaxWidth()) { Text("Library folder speed test") }
            OutlinedButton({ screen = Screen.Results }, Modifier.fillMaxWidth()) {
                Text("Results (${ResultsLog.lines.size})")
            }
            if (busy) CircularProgressIndicator()
            message?.let { Text(it) }
        }
        Screen.Shelf -> ShelfTestScreen()
        Screen.Results -> ResultsScreen()
        is Screen.Reader -> ImageReaderScreen(current.source)
    }
}

@Composable
private fun ResultsScreen() {
    val context = LocalContext.current
    val text = ResultsLog.lines.joinToString("\n")
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Quartz Reader results", text))
            }) { Text("Copy") }
            OutlinedButton({
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share results"))
            }) { Text("Share") }
            OutlinedButton({ ResultsLog.clear() }) { Text("Clear") }
        }
        SelectionContainer {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(ResultsLog.lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
