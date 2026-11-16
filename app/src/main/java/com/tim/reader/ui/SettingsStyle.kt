package com.tim.reader.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tim.reader.theme.fontFamily

/*
 * The look Settings shares with the QuartzInk dashboard: a black title bar, a strip of tabs with
 * the open one in black, and cards drawn as thick black outlines on white.
 */

object SettingsColors {
    val Ink = Color(0xFF111111)
    val Muted = Color(0xFF57534E)
    val Line = Color(0xFFE7E5E4)
    val Danger = Color(0xFFB42318)
}

private val BORDER = 3.dp
private val CARD_SHAPE = RoundedCornerShape(14.dp)
private val BUTTON_SHAPE = RoundedCornerShape(12.dp)

/** Jost, like the dashboard, falling back to the system sans if it can't load. */
private val settingsFont get() = fontFamily("pack:NV Jost")

@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    // White page in any system theme, so the status bar icons stay dark on it.
    val window = LocalActivity.current?.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = true
        onDispose { if (before != null) bars?.isAppearanceLightStatusBars = before }
    }
    val colors = lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        secondary = Color.Black,
        onSecondary = Color.White,
        primaryContainer = Color(0xFFEDEDED),
        onPrimaryContainer = Color.Black,
        secondaryContainer = Color(0xFFEDEDED),
        onSecondaryContainer = Color.Black,
        background = Color.White,
        onBackground = SettingsColors.Ink,
        surface = Color.White,
        onSurface = SettingsColors.Ink,
        surfaceVariant = Color(0xFFF2F2F2),
        onSurfaceVariant = SettingsColors.Muted,
        surfaceContainerHighest = Color(0xFFE6E6E6),
        outline = SettingsColors.Ink,
        outlineVariant = SettingsColors.Line,
        error = SettingsColors.Danger,
    )
    val family = settingsFont
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family, color = SettingsColors.Muted),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides SettingsColors.Ink, content = content)
    }
}

/** The black bar at the top: the app's name, and the page's on the right. */
@Composable
fun SettingsTitleBar(page: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black, CARD_SHAPE)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Quartz") }
                append(" Reader")
            },
            color = Color.White,
            fontFamily = settingsFont,
            fontSize = 26.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(page, color = Color.White.copy(alpha = 0.85f), fontFamily = settingsFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** The strip of tabs under the title bar. It scrolls sideways when the tabs don't all fit. */
@Composable
fun SettingsTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(BORDER, SettingsColors.Ink, CARD_SHAPE)
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .background(if (active) Color.Black else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable(role = Role.Tab, onClickLabel = label) { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    color = if (active) Color.White else SettingsColors.Ink,
                    fontFamily = settingsFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A group of related settings inside a thick black outline. */
@Composable
fun SettingsCard(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(BORDER, SettingsColors.Ink, CARD_SHAPE)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontFamily = settingsFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (hint != null) SettingsHint(hint)
        content()
    }
}

@Composable
fun SettingsHint(text: String) {
    Text(text, color = SettingsColors.Muted, fontFamily = settingsFont, fontSize = 14.sp)
}

/** A dashboard button: a thick black outline, or filled black for the main action. */
@Composable
fun SettingsButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 46.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .background(if (primary) Color.Black else Color.White, BUTTON_SHAPE)
            .border(BORDER, Color.Black, BUTTON_SHAPE)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) Color.White else Color.Black,
            fontFamily = settingsFont,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

/** A setting that's on or off, with a line saying what it does. */
@Composable
fun SettingsSwitch(title: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontFamily = settingsFont, fontSize = 16.sp)
            if (detail != null) Text(detail, color = SettingsColors.Muted, fontFamily = settingsFont, fontSize = 13.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 1.dp, max = 1.dp).background(SettingsColors.Line))
}
