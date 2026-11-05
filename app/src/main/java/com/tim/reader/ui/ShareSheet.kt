package com.tim.reader.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.reader.SHARE_PALETTES
import com.tim.reader.reader.ShareRatio
import com.tim.reader.reader.ShareTemplate
import com.tim.reader.reader.renderShareCard
import com.tim.reader.reader.saveToGallery
import com.tim.reader.reader.shareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A captured page, and the passage and note that can go on a card with it. */
class ShareRequest(val page: Bitmap, val passage: String?, val note: String?)

@Composable
private fun ShareChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) QUICK_BLUE else Color.White),
    ) { Text(label, color = Color.White) }
}

/** A preview of the image, with its layout choices and the buttons to share or save it. */
@Composable
fun ShareOverlay(request: ShareRequest, caption: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasText = request.passage != null || !request.note.isNullOrBlank()
    var template by remember { mutableStateOf(if (hasText) ShareTemplate.Focus else ShareTemplate.Page) }
    var ratio by remember { mutableStateOf(ShareRatio.Square) }
    var palette by remember { mutableStateOf(SHARE_PALETTES.first()) }
    var showNote by remember { mutableStateOf(request.passage == null) }
    val text = (if (showNote) request.note else request.passage).orEmpty()

    val image by produceState<Bitmap?>(null, template, ratio, palette, text) {
        value = withContext(Dispatchers.Default) {
            runCatching { renderShareCard(template, ratio, palette, request.page, text, caption) }.getOrNull()
        }
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
                .pointerInput(Unit) { }
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val shown = image
                if (shown == null) {
                    CircularProgressIndicator()
                } else {
                    val picture = remember(shown) { shown.asImageBitmap() }
                    Image(picture, "Preview", Modifier.fillMaxSize())
                }
            }

            if (hasText) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareTemplate.entries.forEach { option ->
                        ShareChoice(option.label, template == option) { template = option }
                    }
                }
                if (template != ShareTemplate.Page) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShareRatio.entries.forEach { option ->
                            ShareChoice(option.label, ratio == option) { ratio = option }
                        }
                        if (request.passage != null && !request.note.isNullOrBlank()) {
                            ShareChoice("Passage", !showNote) { showNote = false }
                            ShareChoice("Note", showNote) { showNote = true }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SHARE_PALETTES.forEach { option ->
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(Color(option.start), CircleShape)
                                    .border(
                                        if (palette === option) 3.dp else 1.dp,
                                        if (palette === option) QUICK_BLUE else Color.White,
                                        CircleShape,
                                    )
                                    .clickable { palette = option },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareAction("Cancel", Modifier.weight(1f), onClose)
                ShareAction("Save", Modifier.weight(1f)) {
                    val shown = image ?: return@ShareAction
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { saveToGallery(context, shown) }
                        val message = if (saved) "Saved to Pictures" else "Couldn't save the image"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                ShareAction("Share", Modifier.weight(1f)) {
                    val shown = image ?: return@ShareAction
                    scope.launch {
                        val failure = withContext(Dispatchers.IO) {
                            runCatching { shareImage(context, shown) }.exceptionOrNull()
                        }
                        if (failure != null) Toast.makeText(context, "Couldn't share the image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier, border = BorderStroke(1.dp, Color.White)) { Text(label, color = Color.White) }
}
