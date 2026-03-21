package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveInstance
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.ui.currentnote.util.AlarmSymbolUtils

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
        val alarmIds = AlarmSymbolUtils.ALARM_DIRECTIVE_REGEX
            .findAll(line.content)
            .map { it.groupValues[1] }
            .toList()
        for (alarmId in alarmIds) {
            val currentNoteId = getAlarmNoteId(alarmId) ?: continue
            if (currentNoteId != lineNoteId) {
                updates.add(AlarmNoteIdUpdate(alarmId, lineNoteId))
            }
        }
    }
    return updates
}

// ==================== Active recurring instance filter ====================

/**
 * For recurring alarms, keeps only the most recent instance per recurringAlarmId.
 * Prefers PENDING instances; falls back to most recently updated.
 * Non-recurring alarms are kept as-is.
 */
internal fun filterToActiveRecurringInstances(alarms: List<Alarm>): List<Alarm> {
    val nonRecurring = alarms.filter { it.recurringAlarmId == null }
    val recurring = alarms.filter { it.recurringAlarmId != null }

    val latestPerRecurring = recurring
        .groupBy { it.recurringAlarmId }
        .values
        .mapNotNull { instances ->
            instances.firstOrNull { it.status == AlarmStatus.PENDING }
                ?: instances.maxByOrNull { it.updatedAt?.toDate()?.time ?: 0L }
        }

    return nonRecurring + latestPerRecurring
}

// ==================== Alarm symbol migration ====================

/**
 * Migrates plain alarm symbols to directives, given line-to-noteId mapping and alarm data.
 * Returns migrated lines and whether any migration occurred, or null if no symbols found.
 */
internal data class MigrationResult(val lines: List<String>, val migrated: Boolean)

internal fun migrateAlarmSymbolLines(
    lines: List<String>,
    lineNoteIds: List<String?>,
    noteAlarms: Map<String, List<Alarm>>
): MigrationResult? {
    if (lines.none { it.contains(AlarmSymbolUtils.ALARM_SYMBOL) }) return null

    var migrated = false
    val migratedLines = lines.mapIndexed { index, line ->
        if (!line.contains(AlarmSymbolUtils.ALARM_SYMBOL)) return@mapIndexed line

        val noteId = lineNoteIds.getOrElse(index) { null } ?: return@mapIndexed line
        val alarms = noteAlarms[noteId] ?: return@mapIndexed line
        val filtered = filterToActiveRecurringInstances(alarms)
            .sortedBy { it.createdAt?.toDate()?.time ?: 0L }

        val alarmIds = filtered.map { it.id }
        val result = AlarmSymbolUtils.migrateLine(line, alarmIds)
        if (result != line) migrated = true
        result
    }

    return MigrationResult(migratedLines, migrated)
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
 */
internal fun mapResultsByPosition(
    instances: List<DirectiveInstance>,
    uuidResults: Map<String, DirectiveResult>
): Map<String, DirectiveResult> {
    val positionResults = mutableMapOf<String, DirectiveResult>()
    for (instance in instances) {
        val result = uuidResults[instance.uuid] ?: continue
        val positionKey = DirectiveFinder.directiveKey(instance.lineIndex, instance.startOffset)
        positionResults[positionKey] = result
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
