package com.tim.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.BookType
import com.tim.reader.data.ColorSet
import com.tim.reader.data.PageAnimation
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.data.ReaderPrefs
import com.tim.reader.data.SettingScope
import com.tim.reader.data.TextAlignment
import com.tim.reader.library.CameraEdge
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.TwoPageMode
import com.tim.reader.reader.TEXT_FONTS
import com.tim.reader.theme.BookFonts
import kotlin.math.roundToInt

class TextSettingsActions(
    val onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    val onDirection: (ReadDirection) -> Unit,
    val onScope: (SettingScope) -> Unit,
    val onSaveColors: (ColorSet) -> Unit,
    val onRemoveColors: (ColorSet) -> Unit,
    val onRotationLock: (Boolean) -> Unit,
    val onClose: () -> Unit,
)

/** Shows the value while dragging, and only applies it on release, because each change reflows the book. */
@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    onDone: (Float) -> Unit,
) {
    var dragging by remember(value) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Text("$label: ${display(shown)}")
    Slider(
        value = shown,
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let(onDone) },
        valueRange = range,
    )
}

/** Every font in one list, each shown in its own letters. */
@Composable
private fun FontMenuRow(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = TEXT_FONTS.firstOrNull { it.second == current }?.first ?: current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontFamily = BookFonts.family(current), fontSize = 18.sp, modifier = Modifier.weight(1f))
            ListArrow(Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TEXT_FONTS.forEach { (name, family) ->
                DropdownMenuItem(
                    text = { Text(name, fontFamily = BookFonts.family(family)) },
                    onClick = {
                        open = false
                        onPick(family)
                    },
                )
            }
        }
    }
}

@Composable
fun ColumnScope.TextSettings(
    direction: ReadDirection,
    look: ReaderLook,
    type: BookType?,
    scope: SettingScope,
    colorSets: List<ColorSet>,
    fixedLayout: Boolean,
    rotationLocked: Boolean,
    actions: TextSettingsActions,
) {
    val change = actions.onChange
    val scrolling = look.scrolling ?: (direction == ReadDirection.Vertical)
    ReaderSheetCard("Reader settings", actions.onClose) {
        if (type != null) {
            PanelHeading("Apply these settings to")
            ChoiceRow("This book", scope == SettingScope.ThisBook) { actions.onScope(SettingScope.ThisBook) }
            ChoiceRow("All ${type.label} books", scope == SettingScope.ThisType) { actions.onScope(SettingScope.ThisType) }
            ChoiceRow("Everything", scope == SettingScope.AllBooks) { actions.onScope(SettingScope.AllBooks) }
        }

        if (!fixedLayout) {
            PanelHeading("Font")
            FontMenuRow(look.fontFamily) { family -> change { it.copy(fontFamily = family) } }
            SettingSlider("Text size", look.fontSize.toFloat(), 0.5f..3f) { size ->
                change { it.copy(fontSize = size.toDouble()) }
            }

            PanelHeading("Colors")
            ColorSection(
                current = ColorSet(look.pageBackground, look.textColor),
                saved = colorSets,
                onChange = { set -> change { it.copy(pageBackground = set.background, textColor = set.text) } },
                onSave = { actions.onSaveColors(ColorSet(look.pageBackground, look.textColor)) },
                onRemove = actions.onRemoveColors,
            )

            PanelHeading("Layout")
            SettingSlider("Line height", (look.lineHeight ?: 1.4).toFloat(), 1f..2f) { height ->
                change { it.copy(lineHeight = height.toDouble(), bookLayout = false) }
            }
            SettingSlider("Paragraph spacing", (look.paragraphSpacing ?: 0.0).toFloat(), 0f..2f) { spacing ->
                change { it.copy(paragraphSpacing = spacing.toDouble(), bookLayout = false) }
            }
            SettingSlider("Page margins", (look.pageMargins ?: 1.0).toFloat(), 0f..4f) { margins ->
                change { it.copy(pageMargins = margins.toDouble(), bookLayout = false) }
            }
            TextAlignment.entries.forEach { option ->
                ChoiceRow(option.label, look.alignment == option) { change { it.copy(alignment = option, bookLayout = false) } }
            }
            TextButton({
                change {
                    it.copy(lineHeight = null, paragraphSpacing = null, pageMargins = null, alignment = null, bookLayout = true)
                }
            }) { Text("Use the book's layout") }
        }

        PanelHeading("Reading")
        ChoiceRow("Left to right", direction != ReadDirection.RightToLeft) {
            actions.onDirection(ReadDirection.LeftToRight)
        }
        ChoiceRow("Right to left", direction == ReadDirection.RightToLeft) {
            actions.onDirection(ReadDirection.RightToLeft)
        }
        if (!fixedLayout) {
            ToggleRow("Scroll instead of turning pages", scrolling) { on -> change { it.copy(scrolling = on) } }
        }

        if (!scrolling) {
            PanelHeading("Page turn")
            ChoiceRow("Slide", look.animation != PageAnimation.None) { change { it.copy(animation = PageAnimation.Slide) } }
            ChoiceRow("None", look.animation == PageAnimation.None) { change { it.copy(animation = PageAnimation.None) } }

            PanelHeading("Two pages on this device")
            TwoPageMode.entries.forEach { option ->
                ChoiceRow(option.label, DeviceSettings.twoPage == option) { DeviceSettings.updateTwoPage(option) }
            }
        }

        PanelHeading("This device")
        ToggleRow("Keep screen on", DeviceSettings.keepScreenOn) { DeviceSettings.updateKeepScreenOn(it) }
        ToggleRow("Show time left while reading", DeviceSettings.showTimeLeft) { DeviceSettings.updateShowTimeLeft(it) }
        ToggleRow("Lock rotation", rotationLocked, actions.onRotationLock)
        // Kept for this screen held this way, so the Fold's two screens and both orientations each get their own.
        val screen = LocalConfiguration.current
        val screenKey = DeviceSettings.screenKey(screen.smallestScreenWidthDp, screen.screenWidthDp > screen.screenHeightDp)
        PanelHeading("Camera on this screen, held this way")
        CameraEdge.entries.forEach { edge ->
            ChoiceRow(edge.label, DeviceSettings.cameraEdge(screenKey) == edge) { DeviceSettings.updateCameraEdge(screenKey, edge) }
        }
        SettingSlider(
            label = "Camera gap",
            value = DeviceSettings.cameraGap(screenKey).toFloat(),
            range = 0f..96f,
            display = { "${it.roundToInt()} dp" },
        ) { DeviceSettings.updateCameraGap(screenKey, it.roundToInt()) }
    }
}
