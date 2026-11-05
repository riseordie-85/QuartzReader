package com.tim.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Highlight
import org.readium.r2.shared.publication.Locator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A highlight being written or changed. [highlight] is null until it's saved. */
class NoteDraft(val highlight: Highlight?, val locator: Locator, val text: String)

enum class NotesTab(val label: String) {
    Highlights("Highlights"),
    Notes("Notes"),
}

private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)

@Composable
fun ColumnScope.NotesSheet(
    highlights: List<Highlight>,
    tab: NotesTab,
    onTab: (NotesTab) -> Unit,
    onOpen: (Highlight) -> Unit,
) {
    val shown = if (tab == NotesTab.Notes) highlights.filter { it.note.isNotBlank() } else highlights

    ReaderSheetBody {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NotesTab.entries.forEach { option ->
                OutlinedButton(
                    onClick = { onTab(option) },
                    border = BorderStroke(2.dp, if (tab == option) Color(0xFF3B78F6) else Color.White),
                    modifier = Modifier.weight(1f),
                ) { Text(option.label, color = Color.White, fontSize = 18.sp) }
            }
        }
        if (shown.isEmpty()) {
            val hint = "Press and hold text in the book to add one."
            Text(hint, modifier = Modifier.padding(vertical = 16.dp))
        }
        shown.forEachIndexed { index, highlight ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(highlight) }
                    .padding(vertical = 10.dp),
            ) {
                Text(dateFormat.format(Date(highlight.createdAt)), fontSize = 13.sp)
                Text(
                    if (tab == NotesTab.Notes) highlight.note else highlight.text,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index < shown.lastIndex) HorizontalDivider(color = Color.White)
        }
    }
}

/** Writes a note, or reviews a saved highlight with its go to, share, and delete buttons. */
@Composable
fun NoteEditor(
    draft: NoteDraft,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onGoTo: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var note by remember(draft) { mutableStateOf(draft.highlight?.note.orEmpty()) }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { }
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Spacer(Modifier.weight(0.2f))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(PANEL_COLOR, RoundedCornerShape(28.dp))
                    .padding(20.dp),
            ) {
                Text(
                    draft.text,
                    fontSize = 15.sp,
                    color = Color.LightGray,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                BasicTextField(
                    value = note,
                    onValueChange = { note = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    decorationBox = { field ->
                        if (note.isEmpty()) Text("Write a note", color = Color.Gray, fontSize = 24.sp)
                        field()
                    },
                )
                if (draft.highlight != null) {
                    Row {
                        TextButton(onGoTo) { Text("Go to page") }
                        TextButton(onShare) { Text("Share") }
                        TextButton(onDelete) { Text("Delete") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onCancel, Modifier.weight(1f), border = BorderStroke(1.dp, Color.White)) {
                        Text("Cancel", color = Color.White)
                    }
                    OutlinedButton({ onSave(note) }, Modifier.weight(1f), border = BorderStroke(1.dp, Color.White)) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}
