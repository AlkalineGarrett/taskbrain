# Live cross-platform sync

Both platforms sync note changes in real time via a **signal-listener + delta-pull** architecture. The collection-level snapshot listener (`notes WHERE userId = X`) was retired because a resume token older than ~30 minutes bills full target size on reconnect; the new design bounds reconnect cost to one read. See `docs/note-sync-pull-migration.md` for the migration rationale.

## NoteStore (data layer)

Always reflects Firestore truth. No filtering — `rawNotes` updated, `_notes` rebuilt, `changedNoteIds` emitted for every applied delta. The NoteStore does **not** suppress or delay any changes. There is no hot/cool mechanism.

Three mechanisms keep `rawNotes` aligned with Firestore:

1. **`users/{uid}` signal listener** — a 1-doc target. Every notes-collection write is followed by a best-effort `setDoc(merge=true)` on `users/{uid}.lastNoteChange = serverTimestamp()`. When this field changes, the listener triggers a delta pull. Resume cost is bounded (1 read) regardless of token age.
2. **Delta pull** — `notes WHERE userId = X AND updatedAt > (lastSync − 5s) ORDER BY updatedAt`. Bills exactly the changed docs (plus ~handful in the overlap buffer). Triggered by signal-listener fire, app foreground (`ProcessLifecycleOwner.ON_START` / `FirestoreLifecycle.attach`), and listener attach. Pulls are serialized through a per-NoteStore mutex/queue so concurrent triggers don't double-pull.
3. **Foreground count() detection** — once per process/attach, `count()` aggregation vs `rawNotes.size`. Mismatch triggers a full repair pull (drops the watermark, re-pulls every doc), shows a user-visible warning, and resets the watermark. Catches hard-delete divergence that the delta pull can't observe (vanished docs don't appear in `updatedAt > lastSync` queries).

The signal write is best-effort — failures are logged and swallowed. Pull-side self-healing recovers correctness: the next foreground pull picks up the missed change because `updatedAt > lastSync` is canonical.

`UserDocSignal.bump` is **fire-and-forget**: it launches on its own internal scope and returns a `Job` (Android) / `Promise` (web) that callers do not await. The save returns as soon as the notes-collection write commits; the user-doc write lands later. Callers that await it would (a) block the save on an unrelated write and (b) inherit any pathologies of the user-doc write path. Don't reintroduce `await`.

All writes to `notes/{id}` must flow through a path that fires the signal bump. On Android the chokepoint is `NoteRepository.commitInBatches` plus the singleton write helpers (`createNote`, `createMultiLineNote`, `updateShowCompleted`) and the DSL `NoteRepositoryOperations`. On web, mirror. If you add a new write path to `notes`, you must also call `UserDocSignal.bump(db, uid)` after the successful commit — otherwise the change is invisible to other clients until they happen to foreground and trip the count() check.

### Past mistakes worth not repeating

- **2026-05-11 — band-aid timeout on `UserDocSignal.bump`.** The bump was originally implemented as `suspend` + awaited from `commitInBatches`. Unit tests that mocked Firestore returned `Task`s that never completed, freezing 18 tests and stalling the whole Android suite for 18+ minutes. The first fix was a `withTimeoutOrNull(5_000)` cap on the suspension — a band-aid that left the save blocked on the signal write for up to 5 seconds in production for no good reason. The architectural fix was to make the bump truly fire-and-forget (own scope, callers don't await). General rule: a "best-effort" sibling write to a successful operation must never block that operation's call site. If you find yourself reaching for a timeout to make a sibling write "tolerable," ask whether it should be running on the call site's coroutine at all.

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
