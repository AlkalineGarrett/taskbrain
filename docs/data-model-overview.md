# Data Model Overview

Architectural summary of the TaskBrain data model. For detailed Firestore collection schemas, see [schema.md](schema.md).

## Note/Line Schema

Every note line is a separate Firestore document. The Kotlin data class is canonical; the web TypeScript interface mirrors it exactly.

**Kotlin (`Note.kt`):**
```kotlin
data class Note(
    val id: String = "",                            // Firestore auto-generated
    val userId: String = "",
    val parentNoteId: String? = null,               // null = root note
    val content: String = "",                       // single line of text
    val containedNotes: List<String> = emptyList(), // ordered list of child note IDs
    val rootNoteId: String? = null,                 // denormalized root ancestor
    val path: String = "",                          // URL-safe slug for DSL find()
    val state: String? = null,                      // null = active, "deleted" = soft-deleted
    val tags: List<String> = emptyList(),
    val showCompleted: Boolean = true,
    val onceCache: Map<String, Map<String, Any>> = emptyMap(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val lastAccessedAt: Timestamp? = null,
)

data class NoteLine(
    val content: String,       // text with leading tabs for indent level
    val noteId: String? = null // stable ID from NoteLineTracker
)
```

`NoteLine` is an editor-only representation used during the tree-to-flat-lines conversion. It is not persisted directly.

## Tree Structure — Adjacency List + Denormalized Root

The hierarchy uses three pointers per document:

| Field | Purpose |
|---|---|
| `parentNoteId` | Immediate parent (adjacency list) |
| `containedNotes` | Ordered array of child IDs (preserves sibling order) |
| `rootNoteId` | Denormalized pointer to tree root (enables single-query tree loading) |

Conversion between tree and editor representation:
```kotlin
// Tree -> flat indented lines (for editor)
// Walks the parentNoteId tree. Drops containedNotes refs that don't resolve
// to a live child, appends children reachable via parentNoteId that are
// absent from containedNotes, and flags the root as needing a fix.
fun reconstructNoteLines(
    note: Note,
    rawNotes: Map<String, Note>,
    childrenByParent: Map<String, List<Note>>,
): Pair<List<NoteLine>, Boolean>

// Flat indented lines -> tree mutations for save:
// inlined in NoteRepository.saveNoteWithChildren (tab depth → parent chain,
// new nodes get fresh document refs, orphaned descendants get soft-deleted).
```

Indentation is encoded as **leading tab characters** in the editor. Each tab = one nesting level. The tree walk strips/adds tabs during conversion.

## Root Notes vs. Child Lines

- **Root note**: `parentNoteId == null`. Its `content` is the title (first editor line). Its `containedNotes` holds first-level children.
- **Child note**: Separate Firestore document with `parentNoteId` and `rootNoteId` set. Can itself have `containedNotes`. Empty-content lines are also stored as documents — there are no spacer sentinels in `containedNotes`.
- **Query pattern**: `where('rootNoteId', '==', rootId)` fetches the entire subtree in one query.

A child note belongs to exactly one root tree. Lines cannot be children of multiple parents.

## Cross-References — Directive System

Notes reference other notes via inline directives parsed by regex:

```
[view("Shopping List")]       -- embeds another note's content inline
[alarm("abc123")]             -- links to an alarm document
[recurringAlarm("xyz789")]    -- links to a recurring alarm
[once(...)]                   -- cached computed directive
```

`[view(...)]` directives resolve at runtime to the referenced notes, rendered as editable embedded editors. This is the only cross-root linking mechanism — it is a runtime reference, not a stored foreign key.

## Storage Layer — Firestore

Single `notes` collection, one document per note/line:

```
/notes/{noteId}                              -- all note documents (flat collection)
/users/{userId}/alarms/{alarmId}             -- alarm instances
/users/{userId}/schedules/{scheduleId}       -- recurring alarm templates
/users/{userId}/openTabs/{noteId}            -- recent tab state
/users/{userId}/directiveCache/{hash}        -- cached directive results
```

No SQLite or local database. Firestore is the sole persistence layer. Firestore's offline cache provides offline reads.

## Alarm/Task System

Alarms are separate documents linked to note lines by `noteId`. The link from note content to alarm is the directive string `[alarm("id")]` embedded in the line text.

```kotlin
data class Alarm(
    val id: String,
    val noteId: String = "",           // which note line this alarm belongs to
    val lineContent: String = "",      // snapshot of line text at creation
    val dueTime: Timestamp? = null,
    val status: AlarmStatus = PENDING, // PENDING | DONE | CANCELLED
    val stages: List<AlarmStage> = DEFAULT_STAGES,
    val snoozedUntil: Timestamp? = null,
    val recurringAlarmId: String? = null,
)

data class RecurringAlarm(
    val recurrenceType: RecurrenceType, // FIXED (RRULE) or RELATIVE (completion-anchored)
    val rrule: String? = null,
    val currentAlarmId: String? = null,
    // ... stages, endDate, repeatCount, etc.
)
```

Completion status uses checkbox syntax (`☐` / `☑`) on the line itself, not a dedicated field on the Note document.

## Sync Model — Real-Time Firestore Snapshots

Both platforms attach a single collection-level snapshot listener on all notes for the user:

```kotlin
// Android - NoteStore (singleton)
db.collection("notes").whereEqualTo("userId", userId)
    .addSnapshotListener { snapshot, _ ->
        if (snapshot.metadata.hasPendingWrites()) return  // skip local echoes
        if (isFirstSnapshot) handleFirstSnapshot(snapshot.documents)
        else handleIncrementalSnapshot(snapshot.documentChanges)  // only changed docs
    }
```

Web uses the identical pattern via `onSnapshot()`.

**Three-layer sync architecture:**

1. **NoteStore (data layer):** Always reflects Firestore truth. No filtering — processes every snapshot, emits `changedNoteIds`.
2. **Editor (UI layer):** Guards against reloading during active edits (`dirty` flag) and detects content-equal echoes.
3. **Directive cache:** Invalidates per-changed-note, then bumps a global generation counter to catch cross-note staleness (e.g., a `[view]` of a note that changed).

There is no custom sync protocol. Firestore handles conflict resolution (last-write-wins per document), offline queueing, and real-time push to both clients.
