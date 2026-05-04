# Firestore follow-ups (deferred)

Items found during the May 2026 billing-fix work that weren't in scope but
are worth considering later. None of these are urgent — the billing fixes
land independently.

## 1. Web `NoteRepositoryOperations` does extra reads vs Android

`web/src/dsl/runtime/NoteRepositoryOperations.ts` and
`app/src/main/java/org/alkaline/taskbrain/dsl/runtime/NoteRepositoryOperations.kt`
implement the same `NoteOperations` interface for DSL mutations
(`new`, `maybe_new`, `append`, property setters), but the read
patterns diverge. Android consistently consults `NoteStore` (the in-memory
listener cache) and constructs return values locally; web always issues a
fresh `getDoc` after each write and skips the cache entirely.

Per-call read cost (measured in billed reads):

| Method | Android | Web |
|---|---|---|
| `createNote` | 0 (Note constructed from local data after SET) | 1 (`fetchNote` after SET) |
| `updateContent` / `updatePath` | 0 (reads `NoteStore` cache for return) | 1 (`fetchNote` after UPDATE) |
| `appendToNote` | 0–1 (cache hit, then UPDATE) | 2 (`fetchNote` for current content, then `fetchNote` after UPDATE) |
| `getNoteById` | 0–1 (cache first, falls through to `getDoc` on miss) | 1 (always `getDoc`) |
| `findByPath` | 0–1 (`NoteStore.getNoteByPath` first) | 1 (always `getDocs` query) |

DSL mutations are not the hottest path, so the absolute volume is small —
but every directive that calls `new`/`append`/etc. pays this on web and
not on Android. The asymmetry shows up clearly in the new 30-day usage
report under `dsl.fetchNote DOC_GET`.

### Why this exists

The Android version was deliberately written to avoid a re-read: a comment
on `updateAndFetch` reads "Avoids a Firestore re-read that stalls offline
(server-first timeout)." The web version's `updateAndFetch` was written to
the simpler "write then re-read" pattern.

The web version also can't easily mirror Android's `findByPath` cache
shortcut because `noteStore` (web) doesn't expose a `getNoteByPath` API —
only `getRawNoteById`. Android's `NoteStore.getNoteByPath` is what the
DSL ops use.

### What it would take to fix

1. Add `getNoteByPath(path: string): Note | undefined` to
   `web/src/data/NoteStore.ts`. Implementation: linear scan of `rawNotes`
   filtering by `path`. (Android does the same.)
2. Rewrite `NoteRepositoryOperations.ts` to mirror the Android pattern:
   - `getNoteById` / `findByPath` — check cache first, fall through to
     `getDoc` / `getDocs` only on miss.
   - `updateAndFetch` — drop the trailing `fetchNote`; build the returned
     `Note` from `noteStore.getRawNoteById(noteId)` with the new
     content/path layered on, plus `Timestamp.now()` for `updatedAt`.
   - `createNote` — drop the trailing `fetchNote`; construct the returned
     `Note` from the local data the way Android does.
   - `appendToNote` — call the cache-aware `getNoteById`, then
     `updateAndFetch` (which no longer re-reads).
3. Verify the cache-built return values are good enough for callers that
   read `createdAt` / `updatedAt` immediately. The Firestore listener
   echoes the real server timestamps moments later, so anything that
   actually depends on the canonical timestamp should re-read from the
   listener anyway.

### Risk

Low. The change moves web in the direction of Android, which has shipped
this pattern for a while. Test surface: the DSL integration tests cover
`createNote` / `appendToNote` / property setters and run on both
platforms.

## 2. Cross-tab race in `FirestoreUsage.ts` (web)

`web/src/data/FirestoreUsage.ts` loads `localStorage` once at construction,
mutates an in-memory copy, and persists with a 30s debounce. Two tabs of
the same origin each load a snapshot, increment in their own memory, and
write the whole serialized state back — last writer wins. Increments from
the loser tab are silently dropped.

The Android equivalent doesn't have this problem because there's only one
process per app install.

### What it would take to fix

Either:

- **Read-modify-write on persist**: in `persist()`, re-read the current
  `localStorage` value, merge in this tab's increments since the last
  persist (track them in a delta map), then write. Keeps the file local
  to one module.
- **`BroadcastChannel`**: have all tabs publish increments on a channel
  and have a single elected leader own the write. More moving parts;
  probably overkill for diagnostic data.

The read-modify-write approach is simpler and bounded in scope to one
file.

### Risk

Low. Diagnostic data only — worst case a bug in the merge logic
double-counts or drops some increments, which is no worse than the
current behavior.

## 3. Backfill legacy `directiveResults` docs with `userId`

The new `firestore.rules` for `notes/{noteId}/directiveResults/{hash}`
includes a transitional clause:

```
allow get, update, delete: if isAuthenticated() &&
  (!('userId' in resource.data) || existingDataBelongsToUser());
```

The `!('userId' in resource.data)` half lets legacy docs (written before
the userId stamp landed) stay readable. New writes always include
`userId`, so the legacy population shrinks as directives re-run.

If you'd like to clean up faster (and tighten the rule), run a one-time
admin-SDK script:

```js
// Pseudocode — node/admin SDK
const notes = await db.collection('notes').get()
for (const note of notes.docs) {
  const userId = note.data().userId
  const results = await note.ref.collection('directiveResults').get()
  const batch = db.batch()
  for (const r of results.docs) {
    if (!('userId' in r.data())) {
      batch.update(r.ref, { userId })
    }
  }
  if (batch._ops.length > 0) await batch.commit()
}
```

After the backfill completes, simplify the rule to:

```
allow get, update, delete: if isAuthenticated() && existingDataBelongsToUser();
```

### Risk

Low if scripted carefully. Reads every note + every directiveResult once,
which is bounded and one-shot. The rule update is the bigger care-step:
deploy it AFTER confirming the backfill ran to completion (otherwise any
docs missed by the backfill become unreadable).
