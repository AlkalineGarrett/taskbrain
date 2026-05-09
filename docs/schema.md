# Firestore Schema Design

## Collection: `notes`
Path: `/notes/{noteId}`

### Document Structure
**IMPORTANT** Keep in sync with Note.kt (Android) and Note.ts (web)
```json
{
  "id": "String: the ID of this note.",
  "userId": "String: the user ID of the owner.",
  "parentNoteId": "String (noteId) [optional]: Parent note ID",
  "content": "String: The main text content.",
  "createdAt": "Timestamp: Server timestamp of creation",
  "updatedAt": "Timestamp: Server timestamp of last update",
  "tags": [
    "String",
    "String"
  ],
  "containedNotes": [
    "String (noteId)",
    "String (noteId)"
  ],
  "state": "String: null (active), 'deleted' (soft-delete), or 'cut-delete' (parked awaiting paste)",
  "path": "String: Unique path identifier (URL-safe: alphanumeric, -, _, /). Used by DSL find().",
  "rootNoteId": "String (noteId) [optional]: Root note ID for tree queries. Null for root notes, set on all descendants.",
  "showCompleted": "Boolean [optional]: Whether completed (checked) lines are visible. Defaults to true. Per-note toggle.",
  "onceCache": {
    "<normalizedAstKey>": {
      "type": "String: DslValue type (number, string, date, time, datetime, etc.)",
      "value": "Any: Serialized DslValue payload"
    }
  },
  "version": "Number: monotonic per-note write counter. Bumped on every successful save. Defaults to 0 on legacy docs.",
  "lastWriterOpId": "String [optional]: UUID identity stamp from the save batch. Listeners drop echoes whose lastWriterOpId is in the local in-flight set.",
  "containedNotesBase": "Array<String> [optional]: Snapshot of containedNotes the saving client read before applying its diff. Receivers use this for 3-way merge of the children list.",
  "deletionBatchId": "String [optional, format <source>_<uuid>]: Source-tagged batch identifier stamped when state flips to `deleted`. Notes deleted in the same operation share an id. Used by reconstruction (same-batch children render with their deleted parent), undelete (scope the restore set), and forensics (`unknown_*` flags an uninstrumented removal path). Cleared on undelete."
}
```

### Field Explanations
- **content**: First line of the note. Each remaining line goes in its own note and is referenced under containedNotes.
- **tags**: Array of strings for filtering. Use `array-contains` queries.
- **containedNotes**: Array of note IDs representing the contents of the present note (except the first line).
  - Notes are composed recursively.
  - Empty lines don't have their own note. They are indicated by an empty string in the containedNotes array.
- **showCompleted**: Per-note toggle controlling whether checked checkbox lines are visible in the editor. Defaults to `true` (all visible). When `false`, checked lines and their subtrees are hidden behind a "(N completed)" placeholder. Only meaningful on root notes.
- **onceCache**: Persistent cache for `once[...]` expression results. Keys are normalized AST strings (from AstNormalizer), values are serialized DslValues. Enables cross-client consistency: the first client to evaluate `once[datetime]` caches the result, and all subsequent evaluations (including on other devices) return the same value.
- **rootNoteId**: Set on all non-root notes to enable `where('rootNoteId', '==', rootId)` single-query loading. Null for root notes. During tree save, all descendants get this set to the root note's ID. Old-format notes without this field are migrated lazily on save.
- **path**: Unique identifier for the note, used by DSL `find(path: ...)` for pattern matching.
  - URL-safe characters only: alphanumeric, `-`, `_`, `/` (for hierarchy).
  - Can be set via DSL: `[.path: "journal/2026-01-25"]`
- **state**: Lifecycle marker. `null` (or `"active"`) = live, visible in reconstruction and queries. `"deleted"` = soft-deleted, excluded from reconstruction. `"cut-delete"` = doc was cut from its parent and is parked awaiting paste; excluded from reconstruction but can be reclaimed by a paste that knows the doc's id (preserves identity through cut/paste across save boundaries). Use `isLive(state)` from `NoteState.kt` / `NoteState.ts` to test for the live state — never compare strings directly.
- **version**: Advisory monotonic counter. Each save reads the current value from NoteStore and writes `version + 1`. Listeners maintain `lastAppliedVersion[noteId]` per session and drop incoming echoes whose `version <= lastAppliedVersion`. Not enforced via Firestore transactions — concurrent writes from two clients can both produce the same version, in which case Firestore's last-write-wins applies. The version is purely a *receiver-side* gate against stale or out-of-order arrivals.
- **lastWriterOpId**: Identity stamp set by the save batch. The save adds `opId` to its in-flight set before committing; the listener checks each echo's `lastWriterOpId` against that set. Match → our own echo (raw cache update only, no editor reload). No match → external change. Replaces the earlier heuristic stack of `dirty`/`saving`/content-equality guards.
- **containedNotesBase**: Snapshot of `containedNotes` as the saving client read it before applying its diff. Used for 3-way merge of cross-device concurrent edits to the children list (different positions, different inserts, etc.). Only present on writes that staged a 3-way-merge-eligible change.
- **deletionBatchId**: Format `<source>_<uuid>`. Stamped only when `state` flips to `"deleted"` (not on `"cut-delete"`). The `<source>` prefix is one of a fixed vocabulary (`whole-note`, `selection-delete`, `backspace-merge`, `delete-merge`, `paste-replace`, `move`, `external`, `unknown`); see `DeletionSource` in code. Notes deleted in the same operation share the same id. **Reconstruction:** when the parent itself is deleted, children whose `deletionBatchId` matches the parent's are rendered as part of the structure. **Undelete:** restore set is descendants whose `deletionBatchId` matches; the field is cleared on the restored docs. **Forensics:** `unknown_*` flags a removal path that hasn't been instrumented to record its source — drive these to zero.

## Potential future fields

```json
  "resources": [
    {
      "url": "String: URL to the external resource (e.g., Firebase Storage, YouTube)",
      "type": "String: 'image', 'audio', 'video', etc."
    }
  ],
```

### Explanations
- **resources**: Array of objects for external media. `caption` is omitted as it lives in `content`.

## Collection: `noteStats` (per user)
Path: `/users/{userId}/noteStats/{noteId}`

### Document Structure
**IMPORTANT** Keep in sync with NoteStats.kt (Android) and NoteStats.ts (web)
```json
{
  "lastAccessedAt": "Timestamp: Server timestamp of last primary-view open. Used for Recents ordering.",
  "viewCount": "Number: Lifetime count of times the note has been opened as the primary view.",
  "viewedDays": {
    "<YYYY-MM-DD>": "Boolean: true. One entry per distinct local-timezone day the note was opened."
  }
}
```

### Field Explanations
- These fields live in a per-user subcollection (not on the note doc) so view tracking writes don't echo through the notes collection listener and don't trigger any directive cache invalidation. Embedded `[view ...]` directive renders do NOT count as views — only top-level note opens (after a 1.5s dwell) do.
- **`recordView` is a single non-transactional merge-write** (no read needed). The map write `viewedDays.{today}: true` is idempotent — multiple opens on the same day collapse to one entry. `daysViewed`, `firstViewedAt`, and the recency-decayed score are all derived at read time:
  - `daysViewed = Object.keys(viewedDays).length`
  - `firstViewedAt = min(viewedDays.keys())` (lexicographic min on `YYYY-MM-DD`)
  - `decayedScore = Σ exp(-(now - dayMs) / τ)` over each day in the map (τ = 14 days)
- The day key uses the device's local timezone so a note crossing midnight reads as the calendar day the user perceives.
- A 5-minute per-note cooldown on the client suppresses duplicate writes from rapid tab-switching.

## Collection: `openTabs` (per user)
Path: `/users/{userId}/openTabs/{noteId}`

### Document Structure
```json
{
  "noteId": "String: the note ID this tab represents",
  "displayText": "String: first line of note content (truncated for display)",
  "lastAccessedAt": "Timestamp: when this tab was last accessed"
}
```

### Field Explanations
- **noteId**: References the note document. Used as document ID for easy lookup/update.
- **displayText**: Cached first line of note content for quick display without loading full note. Can become stale if a save completes after a tab switch (the post-save handler misses the update). Both platforms run `refreshDisplayTexts` on tab load to cross-reference with NoteStore and fix stale values.
- **lastAccessedAt**: Used to order tabs (most recent first) and enforce 5-tab limit.

## Collection: `alarms` (per user)
Path: `/users/{userId}/alarms/{alarmId}`

### Document Structure
```json
{
  "id": "String: Document ID",
  "userId": "String: ID of the user who owns this alarm",
  "noteId": "String: Associated note/line ID (from NoteLineTracker)",
  "lineContent": "String: Snapshot of line text for display (updated on note save)",
  "createdAt": "Timestamp: Server timestamp of creation",
  "updatedAt": "Timestamp: Server timestamp of last update",
  "upcomingTime": "Timestamp [optional]: When to show in 'Upcoming' list",
  "notifyTime": "Timestamp [optional]: Lock screen notification + status bar icon threshold",
  "urgentTime": "Timestamp [optional]: Lock screen red tint / full-screen red activity threshold",
  "alarmTime": "Timestamp [optional]: Audible alarm with snooze threshold",
  "status": "String: 'PENDING', 'DONE', or 'CANCELLED'",
  "snoozedUntil": "Timestamp [optional]: If snoozed, when to fire again"
}
```

### Field Explanations
- **noteId**: References the specific line's note ID, tracked by NoteLineTracker for stability across edits.
- **lineContent**: Cached copy of the line text for display in alarm lists without loading the full note. Updated on note save.
- **Time thresholds**: Four escalation levels. Each is optional; only set thresholds are active.
  - `upcomingTime` → appears in upcoming list
  - `notifyTime` → lock screen notification + status bar icon
  - `urgentTime` → red tint on lock screen / full-screen red activity
  - `alarmTime` → audible alarm with snooze option
- **status**: Lifecycle state. `PENDING` → active, `DONE` → completed, `CANCELLED` → dismissed.
- **snoozedUntil**: When set, the alarm is silenced until this time, then re-fires.

## Collection: `schedules` (per user)
Path: `/users/{userId}/schedules/{scheduleId}`

### Document Structure
```json
{
  "id": "String: Document ID",
  "userId": "String: ID of the user who owns this schedule",
  "noteId": "String: ID of the note containing the schedule directive",
  "notePath": "String: Note's path for display purposes",
  "directiveHash": "String: Hash of the directive source text (for deduplication)",
  "directiveSource": "String: Full directive source text (for re-parsing at execution)",
  "frequency": "String: 'hourly', 'daily', or 'weekly'",
  "atTime": "String [optional]: Specific time for daily/weekly schedules (HH:MM format)",
  "precise": "Boolean: Whether to use exact timing (AlarmManager) vs approximate (WorkManager)",
  "nextExecution": "Timestamp: Next scheduled execution time",
  "status": "String: 'ACTIVE', 'PAUSED', 'FAILED', or 'CANCELLED'",
  "lastExecution": "Timestamp [optional]: When the schedule last executed",
  "lastError": "String [optional]: Error message from last failed execution",
  "failureCount": "Number: Consecutive failures (max 3 before auto-pause)",
  "createdAt": "Timestamp: Server timestamp of creation",
  "updatedAt": "Timestamp: Server timestamp of last update"
}
```

### Field Explanations
- **directiveHash**: Used to deduplicate schedules — if the directive text hasn't changed, the existing schedule is reused.
- **directiveSource**: Stored so the directive can be re-parsed and executed at runtime without loading the note.
- **frequency**: Execution interval. `hourly`, `daily`, `weekly`.
- **atTime**: For `daily`/`weekly` schedules, the specific time of day (e.g., `"09:00"`). Ignored for `hourly`.
- **precise**: `true` uses AlarmManager for exact timing; `false` uses WorkManager for battery-friendly approximate timing.
- **status**: `ACTIVE` → running, `PAUSED` → auto-paused after 3 failures, `FAILED` → last execution failed, `CANCELLED` → user-cancelled.
- **failureCount**: Tracks consecutive failures. Resets to 0 on success. Schedule auto-pauses at 3.

## Collection: `scheduleExecutions` (per user)
Path: `/users/{userId}/scheduleExecutions/{executionId}`

### Document Structure
```json
{
  "id": "String: Document ID",
  "scheduleId": "String: ID of the associated schedule",
  "userId": "String: ID of the user",
  "scheduledFor": "Timestamp: The original time the schedule was supposed to execute",
  "executedAt": "Timestamp [optional]: When the schedule actually executed (null = missed)",
  "success": "Boolean: Whether the execution was successful",
  "error": "String [optional]: Error message if execution failed",
  "manualRun": "Boolean: True if triggered manually from Schedules screen",
  "createdAt": "Timestamp: Server timestamp when this record was created"
}
```

### Field Explanations
- **scheduledFor**: The intended execution time. Used for audit trail and missed-execution detection.
- **executedAt**: `null` means the execution was missed (e.g., device was off). Non-null means it ran.
- **success**: Only meaningful when `executedAt` is set. `true` = directive executed without error.
- **manualRun**: Distinguishes user-triggered executions from scheduled ones.

## Collection: `directiveCache` (per user)
Path: `/users/{userId}/directiveCache/{directiveHash}`

### Document Structure
```json
{
  "result": "Map [optional]: Serialized DslValue result (null if error)",
  "error": {
    "type": "String: Error category (syntax, type, argument, fieldAccess, validation, unknownIdentifier, circularDependency, arithmetic, network, timeout, resourceUnavailable, permission, externalService)",
    "message": "String: Error message",
    "position": "Number [optional]: Character position in source text"
  },
  "dependencies": {
    "firstLineNotes": ["String (noteId)"],
    "nonFirstLineNotes": ["String (noteId)"],
    "dependsOnPath": "Boolean",
    "dependsOnModified": "Boolean",
    "dependsOnCreated": "Boolean",
    "dependsOnViewed": "Boolean",
    "dependsOnNoteExistence": "Boolean",
    "hierarchyDeps": [
      {
        "path": "String",
        "resolvedNoteId": "String",
        "field": "String",
        "fieldHash": "String"
      }
    ]
  },
  "metadataHashes": {
    "pathHash": "String [optional]",
    "modifiedHash": "String [optional]",
    "createdHash": "String [optional]",
    "viewedHash": "String [optional]",
    "existenceHash": "String [optional]"
  },
  "noteContentHashes": {
    "<noteId>": {
      "firstLineHash": "String",
      "nonFirstLineHash": "String"
    }
  },
  "cachedAt": "Timestamp: When this result was cached"
}
```

### Field Explanations
- **result/error**: Mutually exclusive. One is null, the other contains the cached outcome.
- **dependencies**: Tracks what data the directive depends on, enabling smart cache invalidation.
  - `firstLineNotes`/`nonFirstLineNotes`: Note IDs whose content was read during execution.
  - `dependsOn*`: Flags for metadata-level dependencies (path changes, timestamps, etc.).
  - `hierarchyDeps`: For directives that traverse note hierarchies (e.g., `find(path: ...)`).
- **metadataHashes/noteContentHashes**: Snapshots of data state at cache time. If current hashes differ, the cache is stale.

## Collection: `directiveResults` (per note)
Path: `/notes/{noteId}/directiveResults/{directiveHash}`

Also available at: `/users/{userId}/notes/{noteId}/directiveResults/{directiveHash}` (L2 cache)

### Document Structure
```json
{
  "result": "Map [optional]: Serialized DslValue result",
  "executedAt": "Timestamp: Server timestamp of execution",
  "error": "String [optional]: Error message if execution failed",
  "warning": "String [optional]: Warning if directive had no effect",
  "collapsed": "Boolean: Whether the result is collapsed in the UI"
}
```

### Field Explanations
- **result**: The serialized execution result, displayed inline in the note.
- **error**: If execution failed, the error message to display.
- **warning**: Set when a directive is syntactically valid but has no effect (e.g., `"Uncalled lambda has no effect"`, `"Unused pattern has no effect"`).
- **collapsed**: UI state — whether the user has collapsed this directive's output.
