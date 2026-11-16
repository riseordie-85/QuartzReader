package com.tim.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke

/** The four places the bottom bar goes. Home is the app's name in the header. */
enum class NavTab(val label: String, val glyph: Glyph) {
    Files("Files", Glyph.Folder),
    Library("Library", Glyph.Library),
    Search("Search", Glyph.Search),
    Stats("Stats", Glyph.Chart),
}

/** How much room the bottom bar takes over a page, so lists can scroll their ends clear of it. */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/** The height the bar and its margins take above the system's own navigation. */
val BOTTOM_BAR_SPACE: Dp = 96.dp

/** Room to leave at the end of a list so the bottom bar never covers the last thing in it. */
@Composable
fun BottomBarSpacer(extra: Dp = 0.dp) {
    val space = LocalBottomBarSpace.current
    if (space > 0.dp || extra > 0.dp) Spacer(Modifier.height(space + extra))
}

/** A floating pill in the header's style, with the open page's icon marked by a dot underneath. */
@Composable
fun BottomNav(look: BarLook, current: NavTab?, onTab: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = cornerShape(look.cornerDp)
    val icon = Color(look.iconColor)
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .themedShadow(look.shadow, shape)
                .themedFill(look.fill, shape)
                .themedStroke(look.stroke, shape)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTab.entries.forEach { tab ->
                val active = tab == current
                Column(
                    Modifier
                        .weight(1f)
                        .semantics { selected = active }
                        .clickable(role = Role.Tab, onClickLabel = tab.label) { onTab(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlyphIcon(tab.glyph, icon, Modifier.size(30.dp))
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(4.dp)
                            .background(if (active) icon else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
