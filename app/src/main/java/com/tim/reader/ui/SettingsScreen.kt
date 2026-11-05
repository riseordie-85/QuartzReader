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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.LibraryFolder
import com.tim.reader.library.LibraryOrganizer
import com.tim.reader.theme.Presets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onTestTools: () -> Unit) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
    var organizing by remember { mutableStateOf(false) }
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
        OutlinedButton(
            onClick = {
                organizing = true
                appScope.launch {
                    val ids = repository.state.value.books.map { it.id }
                    val moved = LibraryOrganizer(context).place(ids)
                    withContext(Dispatchers.Main) {
                        organizing = false
                        Toast.makeText(context, "Moved $moved books into place", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !organizing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (organizing) "Organizing" else "Organize library folder") }
        Text("Home style", style = MaterialTheme.typography.titleMedium)
        Presets.all.forEach { (name, theme) ->
            OutlinedButton(
                onClick = { scope.launch { repository.setHomeTheme(theme) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(name) }
        }
        Text("Display", style = MaterialTheme.typography.titleMedium)
        ToggleRow("Split view on large screens", DeviceSettings.splitView) { DeviceSettings.updateSplitView(it) }
        Text("Testing", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onTestTools, Modifier.fillMaxWidth()) { Text("Test tools") }
    }
}
