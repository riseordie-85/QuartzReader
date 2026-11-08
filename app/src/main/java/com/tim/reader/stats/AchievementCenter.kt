package com.tim.reader.stats

import android.content.Context
import com.tim.reader.appScope
import com.tim.reader.data.EventKind
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/** A celebration waiting to slide in at the top of the screen. */
data class GemToast(val title: String, val subtitle: String, val gem: Gem, val id: Long = System.nanoTime())

/**
 * Checks for newly unlocked achievements and reached goals, records them so each is celebrated
 * once, and queues their toasts.
 */
object AchievementCenter {
    private const val MAX_TOASTS = 3

    private val work = Dispatchers.IO.limitedParallelism(1)
    private val queue = MutableStateFlow<List<GemToast>>(emptyList())
    val toasts: StateFlow<List<GemToast>> = queue

    fun dismiss(toast: GemToast) = queue.update { list -> list.filterNot { it.id == toast.id } }

    /** Looks for anything new. Cheap enough to run after each session and whenever the app comes back. */
    fun check(context: Context) {
        val repository = LibraryRepository.get(context.applicationContext)
        appScope.launch(work) {
            val history = repository.readingHistory()
            val state = repository.state.value
            val now = System.currentTimeMillis()
            val fresh = ArrayList<GemToast>()

            for (status in evaluateAchievements(history, state)) {
                if (status.tier < 0) continue
                val newTiers = (0..status.tier).filter { "${status.achievement.id}:$it" !in history.unlocked }
                if (newTiers.isEmpty()) continue
                newTiers.forEach { tier ->
                    repository.addEvent(
                        EventKind.ACHIEVEMENT, null, null, now, null,
                        JSONObject().put("id", status.achievement.id).put("tier", tier),
                    )
                }
                val gem = status.achievement.tiers[status.tier].second
                fresh += GemToast(status.achievement.name, "${gem.label} · ${status.achievement.goal(status.achievement.tiers[status.tier].first)}", gem)
            }

            val today = LocalDate.now()
            for (goal in history.goals) {
                val progress = goalProgress(goal, history, state, today)
                if (!progress.done || progress.key in history.goalsDone) continue
                repository.addEvent(EventKind.GOAL_DONE, null, null, now, null, JSONObject().put("key", progress.key))
                fresh += GemToast("Goal reached", goal.describe(), Gem.Amethyst)
            }

            if (fresh.isEmpty()) return@launch
            // A big first check shouldn't bury the screen in toasts.
            val shown = if (fresh.size > MAX_TOASTS) {
                fresh.take(MAX_TOASTS - 1) + GemToast("And ${fresh.size - MAX_TOASTS + 1} more", "See them all in Stats", Gem.Sapphire)
            } else {
                fresh
            }
            queue.update { it + shown }
            // Goals reached can unlock Goal Getter, which the next check picks up.
            if (fresh.any { it.title == "Goal reached" }) check(context)
        }
    }
}
