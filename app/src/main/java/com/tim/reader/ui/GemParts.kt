package com.tim.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.Gem
import kotlinx.coroutines.delay

private const val TOAST_MS = 3_200L

/** A cut gem in its tier's colors. A null gem is a locked one, drawn as a gray outline. */
@Composable
fun GemIcon(gem: Gem?, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // A brilliant cut seen from the side: a flat crown over a pointed pavilion.
        val crownTop = h * 0.12f
        val girdle = h * 0.40f
        val outline = Path().apply {
            moveTo(w * 0.28f, crownTop)
            lineTo(w * 0.72f, crownTop)
            lineTo(w * 0.96f, girdle)
            lineTo(w * 0.5f, h * 0.94f)
            lineTo(w * 0.04f, girdle)
            close()
        }
        if (gem == null) {
            drawPath(outline, Color(0x66888888), style = Stroke(width = w * 0.06f))
            return@Canvas
        }
        val base = Color(gem.color)
        val shine = Color(gem.shine)
        drawPath(outline, Brush.linearGradient(listOf(shine, base, base.copy(alpha = 0.85f)), Offset(0f, 0f), Offset(w, h)))
        val facets = Color.White.copy(alpha = 0.55f)
        val line = w * 0.025f
        drawLine(facets, Offset(w * 0.04f, girdle), Offset(w * 0.96f, girdle), line)
        drawLine(facets, Offset(w * 0.28f, crownTop), Offset(w * 0.4f, girdle), line)
        drawLine(facets, Offset(w * 0.72f, crownTop), Offset(w * 0.6f, girdle), line)
        drawLine(facets, Offset(w * 0.4f, girdle), Offset(w * 0.5f, h * 0.94f), line)
        drawLine(facets, Offset(w * 0.6f, girdle), Offset(w * 0.5f, h * 0.94f), line)
        drawPath(outline, base.copy(alpha = 0.9f), style = Stroke(width = line * 1.4f))
    }
}

/** Shows queued achievement and goal toasts one at a time at the top of the screen. */
@Composable
fun GemToastHost(onOpen: () -> Unit) {
    val toasts by AchievementCenter.toasts.collectAsState()
    val current = toasts.firstOrNull()
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(current) }
    LaunchedEffect(current?.id) {
        if (current == null) return@LaunchedEffect
        shown = current
        visible = true
        delay(TOAST_MS)
        visible = false
        delay(400)
        AchievementCenter.dismiss(current)
    }
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val toast = shown ?: return@AnimatedVisibility
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 480.dp)
                    .shadow(10.dp, RoundedCornerShape(18.dp))
                    .background(Color(0xF2201A2B), RoundedCornerShape(18.dp))
                    .clickable(onClickLabel = "See achievements") {
                        visible = false
                        onOpen()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GemIcon(toast.gem, 40.dp)
                Column {
                    Text(toast.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(toast.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
