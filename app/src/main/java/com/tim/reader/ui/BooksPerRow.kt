package com.tim.reader.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.tim.reader.data.ViewMode
import com.tim.reader.library.DeviceSettings
import kotlin.math.roundToInt

private const val FEWEST = 2
private const val MOST = 12

/** The saved key for this place and view, which differs between portrait and landscape. */
@Composable
fun booksPerRowKey(place: String, view: ViewMode): String {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return "$place:${view.name}:${if (isLandscape) "landscape" else "portrait"}"
}

/** Changes apply straight away, so the books behind the dialog show the result. */
@Composable
fun BooksPerRowDialog(key: String, automatic: Int, onClose: () -> Unit) {
    val chosen = DeviceSettings.booksPerRow(key)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Books per row") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (chosen == null) "Automatic ($automatic)" else "$chosen books")
                Slider(
                    value = (chosen ?: automatic).toFloat(),
                    onValueChange = { DeviceSettings.updateBooksPerRow(key, it.roundToInt()) },
                    valueRange = FEWEST.toFloat()..MOST.toFloat(),
                    steps = MOST - FEWEST - 1,
                )
                Text("Saved on this device, and separately for portrait and landscape.")
            }
        },
        dismissButton = { TextButton({ DeviceSettings.updateBooksPerRow(key, null) }) { Text("Automatic") } },
        confirmButton = { TextButton(onClose) { Text("Done") } },
    )
}
