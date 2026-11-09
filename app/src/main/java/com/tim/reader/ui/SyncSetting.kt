package com.tim.reader.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.LibrarySync
import com.tim.reader.library.LibraryWatcher

private fun ago(time: Long?) = time?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "not yet"

/** The backup and folder sync switch, with when it last saved and what other devices it has seen. */
@Composable
fun SyncSetting() {
    val context = LocalContext.current
    val status by LibrarySync.status.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep library data in the library folder")
                Text(
                    "Saves progress, history, ratings, goals, shelves and styles in a Library Data folder beside your books. " +
                        "Choosing this folder after a reinstall brings it all back, and any app that syncs the folder keeps your devices in step.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(DeviceSettings.syncFolder, { on ->
                DeviceSettings.updateSyncFolder(on)
                if (on) LibrarySync.save(context)
            })
        }
        if (DeviceSettings.syncFolder) {
            Text("Last saved ${ago(status.lastSaved)} · checked ${ago(status.lastChecked)}", style = MaterialTheme.typography.bodySmall)
            status.devices.forEach { (name, written) ->
                Text("$name saved ${ago(written.takeIf { it > 0 })}", style = MaterialTheme.typography.bodySmall)
            }
            status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ LibrarySync.save(context) }) { Text("Save now") }
                OutlinedButton({ LibrarySync.check(context, force = true) }) { Text("Check other devices") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

/** Whether new files in the library folder are added on their own, and whether this device sorts the Import folder. */
@Composable
fun FolderWatchSettings() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow(
            "Add new books automatically",
            "When the app opens, books that have appeared in Books, Comics, Manga or Manhwa, such as from another device, are added.",
            DeviceSettings.autoAdd,
        ) { on ->
            DeviceSettings.updateAutoAdd(on)
            if (on) LibraryWatcher.check(context, force = true)
        }
        SwitchRow(
            "Sort the Import folder on this device",
            "Books dropped in the library's Import folder are sorted into place. Turn this on for one device only, so synced devices don't sort the same file.",
            DeviceSettings.sortImport,
        ) { on ->
            DeviceSettings.updateSortImport(on)
            if (on) LibraryWatcher.check(context, force = true)
        }
    }
}
