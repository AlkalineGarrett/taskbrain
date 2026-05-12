# Live cross-platform sync

Long-lived subsystems sync via a **signal-listener + delta-pull** architecture, factored on Android into the generic `DeltaPullEngine<T>`. Each subsystem provides a `DeltaPullSink<T>` implementing the type-specific bits (parser, cache shape, apply hook) and the engine handles everything else: signal subscription, hydrate from persistent cache, watermarked delta pull, count() detection, foreground observer, and lifecycle scope. Each channel has its own field on `users/{uid}` and its own watermark in `LastSyncStorage`.

The collection-level snapshot listener pattern (`notes WHERE userId = X`) was retired for these subsystems because a resume token older than ~30 minutes bills full target size on reconnect. Notes and alarms are on signal+pull; recurringAlarms, openTabs, and other small subcollections still use direct listeners (their target size doesn't justify the migration cost). See `docs/note-sync-pull-migration.md`.

## Channels

- `Channel.NOTES` — field `lastNoteChange`, owned by `NoteStore`, watermark `lastSync_NOTES_${uid}`.
- `Channel.ALARMS` — field `lastAlarmChange`, owned by `AlarmRepository`, watermark `lastSync_ALARMS_${uid}` (Android only — alarms are an Android-only feature per `docs/cross-platform-parity.md`).

To add a channel: extend the `Channel` enum (Kotlin) / union (TS), give it a field name, route the subsystem's writes through `UserDocSignal.bump(db, uid, channel)`, and construct a `DeltaPullEngine<T>` with a `DeltaPullSink<T>` that implements `applyFullPull`, `applyDelta`, `localCount`, `raiseSyncWarning`, and `clearSyncWarning`. The engine handles the rest.

## Per-subsystem mechanism

Three mechanisms keep each subsystem's cache aligned with Firestore:

1. **`users/{uid}` signal listener** — a 1-doc target. Every write to the channel's collection is followed by a best-effort `setDoc(merge=true)` on `users/{uid}.<channel.fieldName> = serverTimestamp()`. When this field changes, the listener triggers a delta pull. Resume cost is bounded (1 read) regardless of token age. Each subsystem watches *only its own field* on the same user doc — multiple listeners on the same doc are merged by the Firestore SDK target machinery.
2. **Delta pull** — `<collection> WHERE updatedAt > (lastSync − 5s) ORDER BY updatedAt`. Bills exactly the changed docs (plus ~handful in the overlap buffer). Triggered by signal-listener fire, app foreground (`ProcessLifecycleOwner.ON_START` / `FirestoreLifecycle.attach`), and listener attach. Pulls are serialized through a per-subsystem mutex/queue so concurrent triggers don't double-pull.
3. **Foreground count() detection** — once per process/attach, `count()` aggregation vs the local cache size. Mismatch triggers a full repair pull (drops the watermark, re-pulls every doc) and resets the watermark. Catches hard-delete divergence that the delta pull can't observe (vanished docs don't appear in `updatedAt > lastSync` queries). The notes path also surfaces a user-visible warning; the alarms path stays silent and self-heals.

The signal write is best-effort — failures are logged and swallowed. Pull-side self-healing recovers correctness: the next foreground pull picks up the missed change because `updatedAt > lastSync` is canonical.

`UserDocSignal.bump` is **fire-and-forget**: it launches on its own internal scope and returns a `Job` (Android) / `Promise` (web) that callers do not await. The save returns as soon as the collection write commits; the user-doc write lands later. Callers that await it would (a) block the save on an unrelated write and (b) inherit any pathologies of the user-doc write path. Don't reintroduce `await`.

All writes to a channel's collection must fire the signal bump. On Android:
- Notes — `NoteRepository.commitInBatches` plus the singleton write helpers (`createNote`, `createMultiLineNote`, `updateShowCompleted`) and the DSL `NoteRepositoryOperations`.
- Alarms — every direct write method in `AlarmRepository` (`createAlarm`, `markDone`, `deleteAlarm`, etc.). For alarm writes spliced into a note save via `BatchExtraOp`, the op carries `signalChannel = ALARMS` and `NoteRepository.commitInBatches` bumps both channels.

If you add a new write path to a channel's collection, you must also call `UserDocSignal.bump(db, uid, channel)` after the successful commit — otherwise the change is invisible to other clients until they happen to foreground and trip the count() check.

For **hard delete** specifically: the delta pull can't observe vanished docs, so the calling client must remove the deleted ids from its local cache directly (`noteStore.removeFromRawNotes(ids)` / `AlarmRepository.removeFromCacheLocally(ids)`). Remote clients catch up at the next count() detection.

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
