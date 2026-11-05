package com.tim.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tim.reader.data.Bookmark
import com.tim.reader.data.BookType
import com.tim.reader.data.FitMode
import com.tim.reader.data.PageAnimation
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.engine.Chapter
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.TwoPageMode

/** Where a change to fit, animation, or background is saved. */
enum class SettingScope { ThisBook, ThisType, AllBooks }

private val BACKGROUNDS = listOf(
    0xFF000000,
    0xFF1E1E1E,
    0xFF4A4A4A,
    0xFFFFFFFF,
    0xFFF4ECD8,
    0xFFE9DFC7,
)

class ReaderPanelActions(
    val onDirection: (ReadDirection) -> Unit,
    val onFit: (FitMode) -> Unit,
    val onAnimation: (PageAnimation) -> Unit,
    val onBackground: (Long) -> Unit,
    val onShift: (Boolean) -> Unit,
    val onScope: (SettingScope) -> Unit,
    val onRotationLock: (Boolean) -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun ReaderPanel(
    direction: ReadDirection,
    look: ReaderLook,
    type: BookType?,
    scope: SettingScope,
    shift: Boolean,
    showingTwoPages: Boolean,
    rotationLocked: Boolean,
    actions: ReaderPanelActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reader settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(actions.onClose) { Text("Done") }
            }

            PanelHeading("Read direction")
            ReadDirection.entries.forEach { option ->
                ChoiceRow(option.label, direction == option) { actions.onDirection(option) }
            }

            PanelHeading("Two pages on this device")
            TwoPageMode.entries.forEach { option ->
                ChoiceRow(option.label, DeviceSettings.twoPage == option) { DeviceSettings.updateTwoPage(option) }
            }
            if (showingTwoPages) {
                ToggleRow("Shift pages by one", shift, actions.onShift)
            }

            if (type != null) {
                PanelHeading("Apply these settings to")
                ChoiceRow("This book", scope == SettingScope.ThisBook) { actions.onScope(SettingScope.ThisBook) }
                ChoiceRow("All ${type.label} books", scope == SettingScope.ThisType) { actions.onScope(SettingScope.ThisType) }
                ChoiceRow("Everything", scope == SettingScope.AllBooks) { actions.onScope(SettingScope.AllBooks) }
            }

            PanelHeading("Page fit")
            FitMode.entries.forEach { option ->
                ChoiceRow(option.label, look.fit == option) { actions.onFit(option) }
            }

            PanelHeading("Page turn")
            PageAnimation.entries.forEach { option ->
                ChoiceRow(option.label, look.animation == option) { actions.onAnimation(option) }
            }

            PanelHeading("Background")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                BACKGROUNDS.forEach { color ->
                    val selected = look.background == color
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(Color(color), CircleShape)
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) Color(0xFF4C8DFF) else Color.Gray,
                                CircleShape,
                            )
                            .clickable { actions.onBackground(color) },
                    )
                }
            }

            PanelHeading("This device")
            ToggleRow("Keep screen on", DeviceSettings.keepScreenOn) { DeviceSettings.updateKeepScreenOn(it) }
            ToggleRow("Lock rotation", rotationLocked, actions.onRotationLock)
        }
    }
}

@Composable
private fun PanelHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

@Composable
fun ChapterDialog(chapters: List<Chapter>, onChoose: (Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Chapters") },
        text = {
            LazyColumn {
                items(chapters) { chapter ->
                    Text(
                        chapter.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(chapter.firstPage) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

@Composable
fun BookmarkDialog(
    bookmarks: List<Bookmark>,
    onChoose: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Bookmarks") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet")
            } else {
                LazyColumn {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                bookmark.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onChoose(bookmark.page) }
                                    .padding(vertical = 10.dp),
                            )
                            TextButton({ onRemove(bookmark.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onAdd) { Text("Add this page") } },
        dismissButton = { TextButton(onClose) { Text("Close") } },
    )
}
