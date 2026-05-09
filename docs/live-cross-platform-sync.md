# Live cross-platform sync

Both platforms sync note changes in real time via Firestore snapshot listeners. The architecture has three layers with distinct responsibilities.

## NoteStore (data layer)

Always reflects Firestore truth. No filtering — `rawNotes` updated, `_notes` rebuilt, `changedNoteIds` emitted for every incremental snapshot. The NoteStore does **not** suppress or delay any changes. There is no hot/cool mechanism.

## Editor (UI layer)

Decides whether to reload based on guards:
- `dirty` flag (Android) / `dirtyRef` (web): skip reload while user has unsaved edits.
- `savingRef` (web): skip during save-in-progress.
- Content equality: skip when Firestore echo matches current content (natural echo suppression).

## Directive cache (view directives)

Two invalidation mechanisms work together when notes change:
1. `invalidateForChangedNotes(changedIds)` — eagerly clears cache entries for the changed notes themselves (per-note).
2. `bumpDirectiveCacheGeneration()` — forces `computeDirectiveResults` to re-run, where the `StalenessChecker` detects cross-note staleness (e.g., note A's `[view B]` directive is stale because B changed).

**Critical invariant:** the generation bump MUST be unconditional. Do not gate it on whether `invalidateForChangedNotes` found entries to clear. Per-note clearing only handles direct entries; cross-note dependencies (view directives referencing other notes) rely on staleness checks at lookup time, which only run when `computeDirectiveResults` re-executes via the generation bump.

## Embedded note editors (view directives)

Must update content in place on the same `EditorState` instance — never recreate the session on external changes. Session recreation orphans the Android IME connection, causing the focused line to render empty text. Use `initFromNoteLines(preserveCursor=true)` on the existing session, matching the main editor's pattern.

## Edit session lifecycle

The `EditSessionManager` suppresses staleness checking for the originating note during inline editing. The inline save success path MUST call `endInlineEditSession()` to lift this suppression — otherwise subsequent external changes are permanently blocked from reaching the directive cache.
