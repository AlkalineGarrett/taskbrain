package org.alkaline.taskbrain.data

import kotlin.math.exp
import kotlin.math.ln

enum class NoteSortMode { RECENT, FREQUENT, CONSISTENT }

/**
 * Utility functions for filtering and sorting notes.
 *
 * View tracking lives in a separate per-user subcollection (see [NoteStats]); the
 * sort helpers that depend on it accept a `Map<noteId, NoteStats>` and derive all
 * scores from `viewedDays` (one map entry per distinct local date the note was
 * opened) at read time.
 */
object NoteFilteringUtils {

    private const val VIEW_HALF_LIFE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    fun filterTopLevelNotes(notes: List<Note>): List<Note> {
        return notes.filter { note ->
            note.parentNoteId == null && note.state != "deleted"
        }
    }

    fun sortByUpdatedAtDescending(notes: List<Note>): List<Note> {
        return notes.sortedByDescending { it.updatedAt }
    }

    /** Falls back to updatedAt when a note has no recorded view. */
    fun sortByLastAccessedAtDescending(notes: List<Note>, stats: Map<String, NoteStats>): List<Note> {
        return notes.sortedByDescending { stats[it.id]?.lastAccessedAt ?: it.updatedAt }
    }

    /** Sum of `exp(-Δt/τ)` over each viewed day. Higher = more recent + frequent. */
    fun sortByDecayedScoreDescending(notes: List<Note>, stats: Map<String, NoteStats>, nowMs: Long): List<Note> {
        return sortByPrecomputedScore(notes) { decayedScore(stats[it.id], nowMs) }
    }

    /** daysViewed × ln(1 + daysSinceFirstView). Rewards sustained, long-running access. */
    fun sortByConsistencyDescending(notes: List<Note>, stats: Map<String, NoteStats>, nowMs: Long): List<Note> {
        return sortByPrecomputedScore(notes) { consistencyScore(stats[it.id], nowMs) }
    }

    private inline fun sortByPrecomputedScore(notes: List<Note>, score: (Note) -> Double): List<Note> {
        return notes
            .map { it to score(it) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun filterAndSortNotes(notes: List<Note>): List<Note> {
        return sortByUpdatedAtDescending(filterTopLevelNotes(notes))
    }

    fun filterAndSortNotesByMode(
        notes: List<Note>,
        stats: Map<String, NoteStats>,
        mode: NoteSortMode,
        nowMs: Long,
    ): List<Note> {
        val filtered = filterTopLevelNotes(notes)
        return when (mode) {
            NoteSortMode.RECENT -> sortByLastAccessedAtDescending(filtered, stats)
            NoteSortMode.FREQUENT -> sortByDecayedScoreDescending(filtered, stats, nowMs)
            NoteSortMode.CONSISTENT -> sortByConsistencyDescending(filtered, stats, nowMs)
        }
    }

    fun filterDeletedNotes(notes: List<Note>): List<Note> {
        return notes.filter { note ->
            note.parentNoteId == null && note.state == "deleted"
        }
    }

    fun filterAndSortDeletedNotes(notes: List<Note>): List<Note> {
        return sortByUpdatedAtDescending(filterDeletedNotes(notes))
    }

    private fun decayedScore(stats: NoteStats?, nowMs: Long): Double {
        if (stats == null) return 0.0
        var sum = 0.0
        for (day in stats.viewedDays.keys) {
            val dayMs = parseLocalDateMs(day) ?: continue
            val delta = (nowMs - dayMs).coerceAtLeast(0L)
            sum += exp(-delta.toDouble() / VIEW_HALF_LIFE_MS)
        }
        return sum
    }

    private fun consistencyScore(stats: NoteStats?, nowMs: Long): Double {
        if (stats == null) return 0.0
        val days = stats.viewedDays.keys
        if (days.isEmpty()) return 0.0
        // viewedDays uses ISO YYYY-MM-DD; lexicographic min == earliest date.
        val firstMs = parseLocalDateMs(days.min()) ?: return 0.0
        val span = ((nowMs - firstMs).coerceAtLeast(0L) / MS_PER_DAY).toDouble()
        return days.size * ln(1.0 + span)
    }

    private fun parseLocalDateMs(yyyyMmDd: String): Long? = try {
        java.time.LocalDate.parse(yyyyMmDd)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
