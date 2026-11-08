package com.tim.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.LibraryState
import com.tim.reader.stats.AchievementStatus
import com.tim.reader.stats.evaluateAchievements
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every achievement, unlocked ones first, with the way to the next tier. */
@Composable
fun AchievementsScreen(state: LibraryState, header: HeaderActions) {
    val theme = state.homeTheme ?: return
    val look = theme.header
    val history = rememberHistory(state.books.size)
    val statuses by produceState<List<AchievementStatus>?>(null, history, state) {
        val loaded = history ?: return@produceState
        value = withContext(Dispatchers.Default) {
            evaluateAchievements(loaded, state)
                .sortedWith(compareByDescending<AchievementStatus> { it.tier }.thenBy { it.achievement.hidden })
        }
    }

    var sharing by remember { mutableStateOf<AchievementStatus?>(null) }
    sharing?.let { StatsShareOverlay(StatsCard.Unlocked(it), state) { sharing = null } }

    ThemedBackground(theme.background, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { LibraryHeader(look, header) }
            item {
                Box(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    ThemedText("Achievements", theme.title)
                }
            }
            val loaded = statuses
            if (loaded != null) {
                item {
                    val unlocked = loaded.count { it.tier >= 0 }
                    Panel(look, null) {
                        Body(look, "$unlocked of ${loaded.size} unlocked. Each one has up to four gems: Quartz, Amethyst, Sapphire and Diamond.")
                    }
                }
                items(loaded, key = { it.achievement.id }) { status ->
                    // An unlocked achievement opens its share card.
                    val tap = if (status.tier >= 0) Modifier.clickable(onClickLabel = "Share") { sharing = status } else Modifier
                    Box(tap) { Panel(look, null) { AchievementRow(look, status) } }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun AchievementRow(look: BarLook, status: AchievementStatus) {
    val achievement = status.achievement
    val secret = achievement.hidden && status.tier < 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GemIcon(status.gem, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (secret) "Hidden achievement" else achievement.name, color = Color(look.textColor), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val next = status.next
            when {
                secret -> Muted(look, "Keep reading to find this one", maxLines = 2)
                next == null -> Muted(look, "${status.gem?.label} · ${achievement.goal(achievement.tiers.last().first)}", maxLines = 2)
                else -> {
                    Muted(look, achievement.goal(next), maxLines = 2)
                    Bar(look, status.value.toFloat() / next, Modifier.fillMaxWidth())
                    Muted(look, "${status.value} of $next")
                }
            }
        }
    }
}
