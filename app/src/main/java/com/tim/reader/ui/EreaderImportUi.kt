package com.tim.reader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.library.DeviceSettings
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.EreaderPlan
import com.tim.reader.stats.fetchEreaderExport
import com.tim.reader.stats.formatMinutes
import com.tim.reader.stats.parseEreaderExport
import com.tim.reader.stats.planEreaderImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports reading stats from an Xteink running QuartzInk: straight from its File Transfer page, or
 * from a stats file saved from that page. Shows what it found, and imports on OK. Importing from the
 * same reader again replaces what came from it before.
 */
@Composable
fun EreaderImportButton() {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    var asking by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(DeviceSettings.ereaderAddress) }
    var plan by remember { mutableStateOf<EreaderPlan?>(null) }
    var working by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    // Reads the export text, whichever way it arrived, and works out the plan.
    fun prepare(load: suspend () -> String) {
        working = true
        appScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    val export = parseEreaderExport(load())
                    planEreaderImport(export, repository.state.value.books, repository.readingHistory())
                }
                withContext(Dispatchers.Main) {
                    asking = false
                    if (found.books == 0) toast("${found.export.deviceName} has no reading stats yet") else plan = found
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { toast("Couldn't import: ${error.message}") }
            } finally {
                working = false
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        prepare {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Couldn't open the file")
        }
    }

    OutlinedButton({ asking = true }, Modifier.fillMaxWidth(), enabled = !working) {
        Text(if (working) "Reading the stats…" else "Import from e-reader")
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { if (!working) asking = false },
            title = { Text("Import from e-reader") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "On the reader, open File Transfer and join Wi-Fi. Enter the address it shows, " +
                            "with this phone on the same network.",
                    )
                    OutlinedTextField(
                        address,
                        { address = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Reader address") },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    TextButton(
                        { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !working,
                    ) { Text("Open a saved stats file instead") }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        DeviceSettings.updateEreaderAddress(address)
                        val target = address
                        prepare { fetchEreaderExport(target) }
                    },
                    enabled = !working && address.isNotBlank(),
                ) { Text(if (working) "Connecting…" else "Get stats") }
            },
            dismissButton = { TextButton({ asking = false }, enabled = !working) { Text("Cancel") } },
        )
    }

    plan?.let { found ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Import from ${found.export.deviceName}") },
            text = {
                Column {
                    Text("${found.books} ${if (found.books == 1) "book" else "books"} with ${formatMinutes(found.minutes)} of reading.")
                    if (found.matched > 0) Text("${found.matched} match books in your library.")
                    if (found.elsewhere > 0) Text("${found.elsewhere} will count as read elsewhere.")
                    if (found.finished > 0) Text("${found.finished} finished.")
                    if (found.alreadyFinished > 0) Text("${found.alreadyFinished} already finished here, so not counted twice.")
                    Text(
                        "The reader keeps totals, not sessions, so its time is spread over the days it recorded reading. " +
                            "Anything imported from this reader before is replaced.",
                    )
                }
            },
            confirmButton = {
                TextButton({
                    plan = null
                    appScope.launch {
                        repository.replaceEreaderImport(found.export.deviceId, found.events)
                        AchievementCenter.check(context)
                        withContext(Dispatchers.Main) { toast("Imported reading stats from ${found.export.deviceName}") }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton({ plan = null }) { Text("Cancel") } },
        )
    }
}
