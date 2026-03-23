package org.alkaline.taskbrain.ui.currentnote

import com.google.firebase.Timestamp
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmMarkers
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveInstance
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.ui.currentnote.util.AlarmOverlayMapping
import org.alkaline.taskbrain.ui.currentnote.util.SymbolBadge
import org.alkaline.taskbrain.ui.currentnote.util.SymbolOverlay
import java.util.Date

/**
 * Pure logic extracted from CurrentNoteViewModel for testability.
 * These functions have no Android or ViewModel dependencies.
 */

// ==================== Alarm noteId sync ====================

/**
 * Determines which alarm-to-noteId updates are needed after a save.
 * Returns pairs of (alarmId, newNoteId) for alarms whose noteId is stale.
 */
internal data class AlarmNoteIdUpdate(val alarmId: String, val lineNoteId: String)

internal suspend fun findAlarmNoteIdUpdates(
    trackedLines: List<NoteLine>,
    getAlarmNoteId: suspend (alarmId: String) -> String?
): List<AlarmNoteIdUpdate> {
    val updates = mutableListOf<AlarmNoteIdUpdate>()
    for (line in trackedLines) {
        val lineNoteId = line.noteId ?: continue
        for (occurrence in AlarmMarkers.findDirectiveOccurrences(line.content)) {
            val alarmId = occurrence.id
            val currentNoteId = getAlarmNoteId(alarmId) ?: continue
            if (currentNoteId != lineNoteId) {
                updates.add(AlarmNoteIdUpdate(alarmId, lineNoteId))
            }
        }
    }
    return updates
}

// ==================== Alarm ID extraction ====================

/**
 * Extracted alarm IDs from both [alarm("id")] and [recurringAlarm("id")] directives.
 */
internal data class ExtractedAlarmIds(
    val alarmIds: List<String>,
    val recurringAlarmIds: List<String>
)

/**
 * Extracts all distinct alarm IDs and recurring alarm IDs from directive markers in tracked lines.
 */
internal fun extractAlarmIds(trackedLines: List<NoteLine>): ExtractedAlarmIds {
    val alarmIds = mutableListOf<String>()
    val recurringAlarmIds = mutableListOf<String>()
    for (line in trackedLines) {
        for ((_, id, isRecurring) in AlarmMarkers.findDirectiveOccurrences(line.content)) {
            if (isRecurring) recurringAlarmIds.add(id) else alarmIds.add(id)
        }
    }
    return ExtractedAlarmIds(
        alarmIds = alarmIds.distinct(),
        recurringAlarmIds = recurringAlarmIds.distinct()
    )
}

// ==================== Recurring alarm instance resolution ====================

/**
 * Fetches all instances for a recurring alarm and selects the current one.
 * Shared by all callers that need to resolve "which instance is current" for a recurrence.
 */
internal suspend fun AlarmRepository.resolveCurrentInstance(recurringAlarmId: String): Alarm? {
    val instances = getInstancesForRecurring(recurringAlarmId).getOrNull() ?: return null
    return selectCurrentInstance(instances)
}

// ==================== Recurring alarm instance selection ====================

/**
 * Selects today's alarm instance from a list of recurring instances.
 *
 * Priority:
 * 1. The instance whose due date is today (regardless of status)
 * 2. If no instance is due today, the most recent past instance
 *
 * Used as a fallback when [RecurringAlarm.currentAlarmId] is missing or stale.
 */
internal fun selectCurrentInstance(instances: List<Alarm>, now: Date = Date()): Alarm? {
    if (instances.isEmpty()) return null

    val calendar = java.util.Calendar.getInstance().apply { time = now }
    val todayYear = calendar.get(java.util.Calendar.YEAR)
    val todayDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)

    fun Alarm.isDueToday(): Boolean {
        val dueDate = dueTime?.toDate() ?: return false
        val cal = java.util.Calendar.getInstance().apply { time = dueDate }
        return cal.get(java.util.Calendar.YEAR) == todayYear &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == todayDay
    }

    // Prefer today's instance
    val todayInstance = instances.find { it.isDueToday() }
    if (todayInstance != null) return todayInstance

    // No instance due today — pick the most recent past instance if it's still PENDING,
    // otherwise pick the earliest future instance
    val nowMs = now.time
    val mostRecentPast = instances
        .filter { (it.dueTime?.toDate()?.time ?: Long.MAX_VALUE) <= nowMs }
        .maxByOrNull { it.dueTime?.toDate()?.time ?: 0L }
    if (mostRecentPast?.status == AlarmStatus.PENDING) return mostRecentPast

    return instances
        .filter { (it.dueTime?.toDate()?.time ?: 0L) > nowMs }
        .minByOrNull { it.dueTime?.toDate()?.time ?: Long.MAX_VALUE }
        ?: mostRecentPast
}

// ==================== Symbol overlay computation ====================

/**
 * Computes [SymbolOverlay] list for alarm directives in a line.
 * Returns one overlay per directive, in left-to-right order.
 * Handles both [alarm("id")] and [recurringAlarm("id")] directives.
 */
internal fun computeSymbolOverlays(
    lineContent: String,
    alarmCache: Map<String, Alarm>,
    recurringAlarmCache: Map<String, Alarm>,
    now: Timestamp
): List<SymbolOverlay> {
    val occurrences = AlarmMarkers.findDirectiveOccurrences(lineContent)
    if (occurrences.isEmpty()) return emptyList()

    return occurrences.map { occurrence ->
        val alarm = if (occurrence.isRecurring) {
            recurringAlarmCache[occurrence.id]
        } else {
            alarmCache[occurrence.id]
        }
        if (alarm != null) {
            AlarmOverlayMapping.alarmToOverlay(alarm, now)
        } else {
            SymbolOverlay(symbol = AlarmMarkers.ALARM_SYMBOL, badge = SymbolBadge.None)
        }
    }
}

// ==================== Directive position ====================

/**
 * A position identifier for a directive: line index and start offset within line content.
 * Used for undo/redo to restore expanded state by matching positions.
 */
data class DirectivePosition(val lineIndex: Int, val startOffset: Int)

// ==================== Directive result mapping ====================

/**
 * Converts UUID-keyed directive results to position-keyed results for UI display.
 *
 * Keys use noteId when available (e.g., "noteId:offset"), falling back to
 * effective ID (e.g., "lineId:offset").
 *
 * @param editorNoteIds The noteIds from the editor's LineState — must be the SAME
 *   noteIds that the rendering layer will use for key lookups. This ensures key
 *   generation and key lookup always agree, regardless of whether the tracker
 *   and editor are momentarily out of sync.
 */
internal fun mapResultsByPosition(
    instances: List<DirectiveInstance>,
    uuidResults: Map<String, DirectiveResult>,
    effectiveIds: List<String>
): Map<String, DirectiveResult> {
    val positionResults = mutableMapOf<String, DirectiveResult>()
    for (instance in instances) {
        val result = uuidResults[instance.uuid] ?: continue
        val lineId = effectiveIds.getOrNull(instance.lineIndex) ?: continue
        val key = DirectiveFinder.directiveKey(lineId, instance.startOffset)
        positionResults[key] = result
    }
    return positionResults
}

/**
 * Finds positions of all expanded (non-collapsed) directives.
 */
internal fun findExpandedPositions(
    instances: List<DirectiveInstance>,
    results: Map<String, DirectiveResult>
): Set<DirectivePosition> {
    val expandedUuids = results.filter { !it.value.collapsed }.keys
    if (expandedUuids.isEmpty()) return emptySet()

    return instances
        .filter { it.uuid in expandedUuids }
        .map { DirectivePosition(it.lineIndex, it.startOffset) }
        .toSet()
}

/**
 * Merges fresh directive results with existing collapsed state.
 * Preserves the collapsed flag from [currentResults] for each UUID.
 */
internal fun mergeDirectiveResults(
    freshResults: Map<String, DirectiveResult>,
    currentResults: Map<String, DirectiveResult>?
): Map<String, DirectiveResult> {
    return freshResults.mapValues { (uuid, result) ->
        val existingCollapsed = currentResults?.get(uuid)?.collapsed ?: true
        result.copy(collapsed = existingCollapsed)
    }
}

// ==================== Note ID resolution ====================

/**
 * Resolves noteId conflicts at save time.
 *
 * When multiple lines claim the same noteId (e.g., after a split), the line with
 * the most content (excluding prefix) keeps the noteId. Others get null, causing
 * a new Firestore document to be created.
 *
 * For lines with multiple noteIds (from merges), the first (primary) noteId is used.
 */
internal fun resolveNoteIds(
    contentLines: List<String>,
    lineNoteIds: List<List<String>>
): List<NoteLine> {
    data class LineCandidate(val index: Int, val noteId: String, val contentLength: Int)

    val candidates = contentLines.mapIndexedNotNull { index, text ->
        val primaryId = lineNoteIds.getOrElse(index) { emptyList() }.firstOrNull()
            ?: return@mapIndexedNotNull null
        val contentLength = LineState.extractPrefix(text).let { prefix ->
            text.substring(prefix.length).length
        }
        LineCandidate(index, primaryId, contentLength)
    }

    val noteIdWinner = candidates
        .groupBy { it.noteId }
        .mapValues { (_, candidates) -> candidates.maxByOrNull { it.contentLength }!!.index }

    return contentLines.mapIndexed { index, text ->
        val ids = lineNoteIds.getOrElse(index) { emptyList() }
        val primaryId = ids.firstOrNull()
        val resolvedId = if (primaryId != null && noteIdWinner[primaryId] == index) {
            primaryId
        } else {
            null
        }
        NoteLine(text, resolvedId)
    }
}
