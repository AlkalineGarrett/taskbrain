# Note sync: pull-with-signal migration plan

Plan for replacing NoteStore's full-collection snapshot listener with a delta-pull mechanism, driven by a live signal on the user doc. Written 2026-05-11 against the alpha codebase. Single-user context; the architectural choices reflect that — see "Out of scope" before generalizing.

## Why this exists

NoteStore currently listens to `notes WHERE userId = X` as a snapshot listener. Symptom in production audit logs: when the Android SDK reconnects with a resume token older than ~30 minutes, Firestore's documented behavior is to bill the resume **as if it were a brand-new query** — full target size in reads. With the current ~1800-doc target, every long-idle reconnect can cost ~2K reads. This is documented Firebase pricing, not a bug, but the cost is unbounded with respect to actual change volume.

See `live-cross-platform-sync.md` for the current architecture and `firestore-efficiency.md` for the cost-tracking primitives.

## What this plan does and doesn't change

**Changes:** how NoteStore is populated and kept fresh.

**Does not change:**
- `NoteStore`'s contract: alignment with Firestore for the data it holds (see memory `feedback_live_sync_architecture`). It already need not be complete; it must not be stale.
- `find()` and the DSL evaluator. NoteStore still holds all the user's notes at alpha scale; `find()` keeps reading `env.getNotes()` locally.
- `MetadataHasher` and directive caching. Same reasoning.
- Per-tab editor listeners (`notes WHERE rootNoteId = X AND userId = Y`). These stay; they're cheap and provide real-time sync for the currently-open editor.
- Echo suppression at the editor level (`dirty` / `saving` guards). The pull is structured the same way as the listener for echo purposes.
- Soft-delete semantics. Deletions remain `state = "deleted"` writes that bump `updatedAt`.
- The line-identity invariant (every line is a Firestore doc; sentinels never content-matched).

## Target architecture

Two mechanisms replace the always-on full listener:

1. **Live signal**: a snapshot listener on `users/{uid}`. Any note write bumps `users/{uid}.lastNoteChange = serverTimestamp()`. When the listener fires with a changed `lastNoteChange`, clients trigger a pull. Single-doc listener — bounded resume cost (1 read) regardless of token age.
2. **Delta pull**: `notes WHERE userId = X AND updatedAt > (lastSync - 5s) ORDER BY updatedAt`. Bills exactly the changed docs (plus ~handful in the overlap buffer). Triggered by signal-listener fire, app foreground, and post-login.

Correctness contract: `updatedAt > lastSync` is canonical. The `lastNoteChange` field is a low-latency hint; if it's stale, the next foreground pull (or per-tab listener fire) catches up. This keeps writers free of an atomicity burden between the note write and the signal bump.

## Pre-implementation verification

Confirm in the current code before starting:

1. Every existing `notes` write site sets `updatedAt`. Grep:
   ```
   rg "collection\(['\"]notes['\"]\)\.|notesCollection\." app/src/main web/src
   ```
   Audit each call site. If any path writes to `notes/{id}` without setting `updatedAt = serverTimestamp()`, the delta pull will silently miss its changes. **This must be fixed first** — independent of the rest of the migration.

2. Firestore SDK versions support `count()` aggregations on both platforms (Firebase Android SDK ≥ 24.10, JS SDK ≥ 9.18). Confirm via the build files. If a platform is too old, the detection step has to fall back to a `get()` query — still works, just costs more reads.

3. The `users/{uid}` document exists for the active user. If it doesn't (some accounts may lack it), the listener attach will fail. Confirm or add a create-if-missing on login.

## Implementation steps

Land all steps in a single PR on both platforms. Intermediate states leave behind correctness gaps.

### Step 1 — Centralize note writes

**Goal**: every path that writes to `notes/{id}` goes through one wrapper function per platform. Without this, the migration is fragile to "writer forgot to bump `lastNoteChange`" bugs.

**Android** (`app/src/main/java/org/alkaline/taskbrain/data/NoteRepository.kt`):

- Add a private method `writeNoteDoc(...)` (or batched variant) that:
  - Stamps `updatedAt = FieldValue.serverTimestamp()` on the doc data.
  - Performs the write (set/update/batch).
  - After successful write, fires a best-effort sibling write: `db.collection("users").document(uid).update("lastNoteChange", FieldValue.serverTimestamp())`. Catch and log failures; do not propagate them — the note write already succeeded, and the pull-side self-healing covers a missed bump.
  - Records via `FirestoreUsage.recordWrite`.
- Route all existing `notesCollection.add/update/set/delete` calls in this file through it.
- Route `app/src/main/java/org/alkaline/taskbrain/dsl/runtime/NoteRepositoryOperations.kt` through the same wrapper — inject `NoteRepository` if necessary.
- Soft-delete paths (`softDeleteNote`, etc.) route through the wrapper.
- For batched writes (multi-doc saves), bump `lastNoteChange` **once per batch**, not per doc.

**Web** (`web/src/data/NoteRepository.ts` and related): mirror the Android changes. Same wrapper shape, same constraints.

**Audit gate**: after this step, the only places that touch `db.collection("notes")` (Android) / `collection(db, 'notes')` (web) for writes should be inside the wrapper. Add a comment on the wrapper documenting the invariant. Consider an eslint/detekt rule if available.

### Step 2 — `lastSync` storage

Per-device, per-user, local-only:

- **Android**: `SharedPreferences("taskbrain_prefs")`, key `"lastSync_${uid}"`. Stored as Long milliseconds since epoch (Timestamp serialized).
- **Web**: `localStorage`, key `"lastSync_${uid}"`. Same encoding.

Read on listener attach / first pull. Write **after** apply succeeds. Missing value → treat as 0 → triggers a full first pull.

### Step 3 — User-doc signal listener and delta pull

Replace the current `notes`-collection listener in NoteStore.

**Android** (`app/src/main/java/org/alkaline/taskbrain/data/NoteStore.kt`):

- Remove `db.collection("notes").whereEqualTo("userId", userId).addSnapshotListener(...)`.
- Add `db.collection("users").document(uid).addSnapshotListener(...)`. On each snapshot, if `lastNoteChange` is more recent than the value at last pull, trigger a delta pull.
- Add `pullDelta()`:
  ```
  notes WHERE userId = X AND updatedAt > (lastSync - 5s) ORDER BY updatedAt
  ```
  Apply each returned doc idempotently to NoteStore (replace-by-id; reconstruction handles `state == "deleted"` as removal). After all docs applied, advance `lastSync` to the max `updatedAt` in the response; persist.
- Also trigger `pullDelta()` on app foreground (`ON_START` lifecycle) and on first listener attach.
- The first pull (when `lastSync` is 0 or missing) effectively becomes a full pull. Acceptable one-time cost on first run.

**Web** (`web/src/data/NoteStore.ts`): mirror. The web lifecycle (R2 / `FirestoreLifecycle`) already has attach/detach hooks — attach kicks off the listener + an initial pull; detach drops both. See `feedback_live_sync_architecture` for the contract.

### Step 4 — Per-tab listeners stay

`notes WHERE rootNoteId = X AND userId = Y` listeners for currently-open editors remain unchanged. They still give real-time sync for the active note and serve as a second source of "something changed; trigger a pull" if the user-doc signal is delayed.

### Step 5 — Detection and loud-error repair

On app foreground (Android `ON_START`; web visibility-visible), once per session, after attaching the listener:

- Issue `db.collection("notes").whereEqualTo("userId", uid).count().get()` — Firestore aggregation query. ~1 billed read.
- Compare result to NoteStore's live count (filter the same way: e.g., not `state == "deleted"` if the aggregation uses that filter).
- **Mismatch**: trigger full repair pull (no watermark filter), replace NoteStore wholesale, reset `lastSync`. Surface a banner/toast through the existing user-visible error path (whatever `NoteStore.raiseWarning` routes to). Log the divergence detail: which ids local has that server doesn't, and vice versa.
- **Match**: log success at debug level; no UI noise.

Surface, don't swallow — see memory `feedback_surface_errors`.

### Step 6 — Update `FirestoreUsage` tracking

In `app/src/main/java/org/alkaline/taskbrain/data/FirestoreUsage.kt` and the web equivalent:

- Add read categories `PULL_DELTA` and `PULL_FULL_REPAIR`.
- The pull paths record `recordRead(...)` with the actual number of docs returned. This replaces the prior listener-based recording that undercounted on resnaps.
- Remove or deprecate the listener-specific categories (`LISTENER_INITIAL_FRESH`, `LISTENER_UPDATE_FRESH`, etc.) that no longer fire from the notes-collection listener. The per-tab and user-doc listeners still use listener categories — keep those.

### Step 7 — Tests

- **Unit**: pull-applies-idempotently; lastSync advances correctly; lastSync does not advance on apply failure; detection-on-mismatch fires repair; centralized wrapper bumps `lastNoteChange`.
- **Cross-platform parity** (`cross-platform-parity.md`): both platforms exercise the same flows.
- **Emulator-backed integration test** (Android `connectedAndroidTest`, web Playwright): drive a write through one client, confirm the other client's NoteStore reflects it after foreground (or signal fires); confirm a manually-induced divergence (delete a doc directly via admin SDK while the client is offline) is caught by detection on next foreground.

## Validation criteria

- Old listener (`notes WHERE userId = X`) is gone from both platforms (grep confirms).
- A simulated 4-hour idle followed by foreground costs <50 reads on the dashboard (signal listener resume + count check + delta pull for actual changes), not ~2K.
- Detection fires and surfaces an error when an out-of-band write is made directly to Firestore while the client is offline.
- All existing tests pass.
- `FirestoreUsage` report no longer materially undercounts vs the dashboard.

## Risks and mitigations

1. **Missed `lastNoteChange` bump**: best-effort, but the centralized wrapper makes it unlikely. Pull-side self-healing (`lastSync` advances to actual `max(updatedAt)`) recovers correctness even if the bump never landed; only real-time-ness is lost for that change.
2. **First-run after deploy**: empty `lastSync` triggers a full pull. ~1800 reads, one-time. Expected.
3. **Write rate doubling**: every note write also bumps the user doc. Single-user, single user-doc — fine. At scale, the user doc becomes a per-user hotspot (Firestore's per-doc sustained write rate is ~1/s); not a concern at alpha, would force a different signal mechanism at scale.
4. **Per-tab listener resume cost**: still subject to the same 30-min cliff in theory. Target size is small (one note's descendants, tens to hundreds of docs), so worst-case cost is bounded and not a priority.
5. **Orphaned Firestore SDK persistent cache**: when the notes-collection listener goes away, the SDK's on-disk cache for that target becomes unused. SDK GCs it eventually. If desired, a one-time clear on the first run of the new version avoids confusion.
6. **`count()` aggregation pre-2023 SDK**: verify SDK versions. Fallback to `get()` query for the detection step if needed.

## Out of scope (deliberate)

- Reducing NoteStore to a non-complete cache. At alpha scale, keeping all notes locally is fine; the cost target is read traffic, not memory. When `find()` / DSL scaling becomes the bottleneck, see `scaling-risks.md`.
- Cloud Functions for any part of this. The signal bump is client-side best-effort plus self-healing.
- Migrating per-tab listeners to pull. They're cheap; complexity not worth it.
- Changing the per-line-doc data model (see memory `project_per_line_doc_foundational`).
- Replacing soft-delete with hard-delete.

## Commit shape

One PR, both platforms, all steps. Intermediate states (e.g., centralized writes deployed but listener not yet replaced) leave correctness gaps. Larger commit is acceptable per project convention.

## After landing

Update:
- `docs/note-store-architecture.md` — replace the listener-based architecture description with the pull-based one.
- `app/src/main/java/org/alkaline/taskbrain/data/CLAUDE.md` and `web/src/data/CLAUDE.md` — reference this doc and the new flow.
- `feedback_live_sync_architecture` memory entry, if its description needs to reflect "pull, not listener."
- `firestore-efficiency.md` if it cites the listener as the dominant read source.
