package com.tim.reader.ui

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.data.LibraryRepository
import com.tim.reader.library.LibraryFolder
import com.tim.reader.theme.Presets
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onTestTools: () -> Unit) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    val scope = rememberCoroutineScope()
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
        Text("Home style", style = MaterialTheme.typography.titleMedium)
        Presets.all.forEach { (name, theme) ->
            OutlinedButton(
                onClick = { scope.launch { repository.setHomeTheme(theme) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(name) }
        }
        Text("Testing", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onTestTools, Modifier.fillMaxWidth()) { Text("Test tools") }
    }
}
