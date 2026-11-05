package com.tim.reader.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

/** The light workspace the customizing screens share. */
object EditorColors {
    val Panel = Color(0xFFCFD1D5)
    val Card = Color.White
    val Ink = Color(0xFF2F3134)
    val Muted = Color(0xFF6F7277)
    val Line = Color(0xFFE5E6E8)
    val Border = Color(0xFF9A9CA0)
    val Accent = Color(0xFF2B7DE9)
    val Well = Color(0xFFF3F4F5)
    val Warning = Color(0xFFB26A00)
}

private val CONTROL_SHAPE = RoundedCornerShape(16.dp)

/** Tighter than the default, so labels fit on phones. */
val EDITOR_BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    // The panel is light in any system theme, so the status bar icons have to stay dark on it.
    val window = LocalActivity.current?.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = true
        onDispose { if (before != null) bars?.isAppearanceLightStatusBars = before }
    }
    val colors = lightColorScheme(
        primary = EditorColors.Accent,
        onPrimary = Color.White,
        surface = Color.White,
        onSurface = EditorColors.Ink,
        onSurfaceVariant = EditorColors.Muted,
        outline = EditorColors.Border,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides EditorColors.Ink, content = content)
    }
}

/** One white card of related controls, with an optional switch or button beside its heading. */
@Composable
fun EditorCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(EditorColors.Card, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

/** A card that an on and off switch opens and closes. */
@Composable
fun EditorSwitchCard(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    EditorCard(title, trailing = { EditorSwitch(checked, onChange) }) {
        if (checked) content()
    }
}

@Composable
fun EditorSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.Accent, checkedThumbColor = Color.White),
    )
}

@Composable
fun EditorSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        EditorSwitch(checked, onChange)
    }
}

@Composable
fun EditorDivider() {
    HorizontalDivider(color = EditorColors.Line)
}

/** Shows its value in plain numbers, and updates as it moves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String = { it.roundToInt().toString() },
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(display(value), color = EditorColors.Muted)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            thumb = {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(EditorColors.Ink, CircleShape),
                )
            },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(EditorColors.Border, CircleShape),
                )
            },
        )
    }
}

/** Two to four choices joined into one control, with the current one filled in. */
@Composable
fun EditorSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CONTROL_SHAPE)
            .border(1.dp, EditorColors.Border, CONTROL_SHAPE),
    ) {
        options.forEachIndexed { index, label ->
            val isOn = index == selected
            Text(
                label,
                color = if (isOn) Color.White else EditorColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .background(if (isOn) EditorColors.Ink else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 6.dp, vertical = 11.dp),
            )
        }
    }
}

/** The small downward arrow on anything that opens a list. */
@Composable
fun ListArrow(color: Color = EditorColors.Ink) {
    Canvas(Modifier.size(16.dp, 10.dp)) {
        val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2, size.height)
            close()
        }
        drawPath(arrow, color)
    }
}

/** A secondary button. It's outlined, which leaves one filled button as the main action. */
@Composable
fun EditorOutlineButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick,
        modifier,
        shape = CONTROL_SHAPE,
        border = BorderStroke(1.5.dp, EditorColors.Border),
        contentPadding = EDITOR_BUTTON_PADDING,
    ) {
        Text(
            label,
            color = EditorColors.Ink,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EditorMainButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick,
        modifier,
        enabled = enabled,
        shape = CONTROL_SHAPE,
        colors = ButtonDefaults.buttonColors(containerColor = EditorColors.Ink, contentColor = Color.White),
    ) { Text(label, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** A labeled setting that opens a list, with the current choice showing on the right. */
@Composable
fun <T> EditorListRow(label: String, options: List<Pair<String, T>>, value: T?, emptyLabel: String, onChange: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.second == value }?.first ?: emptyLabel
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                shown,
                color = if (value == null) EditorColors.Muted else EditorColors.Ink,
                fontWeight = if (value == null) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ListArrow(EditorColors.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(emptyLabel) },
                onClick = {
                    open = false
                    onChange(null)
                },
            )
            options.forEach { (name, option) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

