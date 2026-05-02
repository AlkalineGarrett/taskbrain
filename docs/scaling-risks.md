# Scaling risks

Catalog of code paths whose cost grows with workload size. Each entry names
the path, identifies the dimension that scales (M = total live notes for the
user, N = note size in lines / descendants, K = view-directive notes shown
on screen), gives a current-cost note, and a fix sketch.

This doc complements `save-refactor-followups.md`. Followups are deferred
features and tech-debt; this doc is specifically about workload growth.

Severities reflect what we'd notice today on a realistic workspace:
- **High** — observable on workspaces in the hundreds of notes / dozens of
  view-directive results.
- **Medium** — observable in the thousands of notes or under sustained load.
- **Low** — pathological cases only; bounded enough that profiling would
  flag it before users do.

---

## Memory

### NoteStore in-memory mirror — O(M) (architectural)
- **Path:** `NoteStore.rawNotes` holds every note + descendant for the user,
  populated by the live Firestore listener. All save-side reads (descendant
  set, parent indexes, content-drop guard, three-way merge) read from this
  in-memory map.
- **Cost:** O(M) memory; first-snapshot latency grows with M.
- **Severity:** **Medium.** A user with 5–10k notes loads all of them on
  every cold start. Firestore client + reconstruction memory dominate; the
  app keeps reading them so eviction isn't an option without redesign.
- **Fix sketch:** Out of scope for incremental work. Ranges from "load only
  the active note's tree on demand" (breaks offline-edit + save planner
  reads) to "page rawNotes by recency" (complex). The per-line-doc model
  amplifies M by ~5–10×.

### Cut-delete buffer — bounded but session-lifetime
- **Path:** `NoteStore.pendingCuts` (Map<lineId, content>). Persists across
  saves until the line is reclaimed via paste or parked via cut-delete write.
- **Cost:** O(cuts in current session). User-bounded.
- **Severity:** **Low.** A power-user cutting hundreds of lines without
  pasting is a real but contained scenario.

### View-session map — O(K) per active editor
- **Path:** `InlineEditState.viewSessions` (Android) /
  `InlineSessionManager.sessions` (web). One `InlineEditSession` per
  view-directive note, each owning an `EditorState`, `EditorController`,
  `UndoManager`, plus a `localBases` snapshot.
- **Cost:** O(K) memory; each session also holds undo history.
- **Severity:** **Low** for typical K (≤20). A view directive returning
  hundreds of notes would balloon undo memory specifically.

---

## Hot-path CPU

### `NoteStore.snapshotLocalBases(rootId)` — O(M) per call
- **Path:** Captures `containedNotes` for root + every live descendant of
  `rootId`. Implementation calls `getDescendantIds(rootId)`, which scans
  `rawNotes` (the entire user's notes), then iterates the descendant set.
- **Called from:** every edit-session start AND after every successful save
  via `captureLocalBase` / `refreshLocalBase` (`useEditor.ts`,
  `CurrentNoteViewModel`, both `InlineEditSession`s).
- **Cost:** O(M + N) per save where M is total notes, N is descendant count.
- **Severity:** **Medium.** Save frequency × M grows fast on large
  workspaces. The descendant-walk is unavoidable; the M-scan is not.
- **Fix sketch:** maintain an `id → descendantIds` index on NoteStore,
  invalidated incrementally by the listener delta. Same fix would speed up
  `findConcurrentSubtree`'s seeded BFS.

### `findConcurrentSubtree` parent→children index rebuild
- **Path:** `NoteRepository.{kt,ts}` — `planSave` BFS for concurrent
  additions. Builds `childrenByParent` over `existingDescendantIds` once per
  save.
- **Cost:** O(N) per save — bounded by note tree size, but the same data is
  built three times across `planSave` (descendant survival, merge skip
  detection, this BFS).
- **Severity:** **Low** at typical N (<200). The redundant rebuild is
  cheap-but-pointless.
- **Fix sketch:** share a single parent→children index across the three
  consumers in `planSave`.

### Per-descendant `getRawNoteById` in `planSave` — N+1 lookups
- **Path:** Three independent passes over descendants in `planSave`:
  - merge precomputation calls `getRawNoteById(id)` per descendant.
  - skip-detection pass calls it again.
  - write loop calls it again for the stamp.
- **Cost:** 3N hash-map lookups per save.
- **Severity:** **Low.** Hash-map lookups are cheap; the inefficiency is
  structural rather than CPU-bound.
- **Fix sketch:** snapshot `Map<id, Note>` of relevant descendants once at
  planSave entry; pass it through.

### Listener `Date.now()` in `isOurEcho` — N timestamps per snapshot
- **Path:** `NoteStore` echo-suppression check. For an N-doc snapshot, runs
  N timestamp reads + N Map gets.
- **Cost:** O(N) per snapshot.
- **Severity:** **Low** at typical snapshot sizes; potentially **Medium**
  on a 1000-doc snapshot during cold start.
- **Fix sketch:** hoist `Date.now()` once per snapshot into the calling loop.

---

## Listener / coordination

### `awaitNoteLoaded` — per-call listener fan-out — O(K) listeners
- **Path:** Web: `subscribeChangedNoteIds(...)` per call. Android:
  `changedNoteIds.first { ... }` per call (each creates its own
  `SharedFlow` collector).
- **Cost:** With K parallel `awaitNoteLoaded` calls (e.g. on a view
  directive returning K notes), every Firestore snapshot dispatches K
  callbacks/collectors. Net: O(K × snapshots).
- **Severity:** **Medium** for K ≤ 100; degrades non-linearly thereafter.
  Android `changedNoteIds` has `extraBufferCapacity=1, DROP_OLDEST` —
  bursty snapshots can drop emissions before just-registered collectors
  dispatch, leading to spurious 1500ms timeouts → Firestore one-shot
  fallback.
- **Fix sketch:** central waiter index on NoteStore, e.g.
  `Map<noteId, List<resolver>>`. Listener loop drains the relevant entry
  on every emission. One pass over the changed set, per snapshot.

### `awaitPendingSave` per `loadNoteLinesAwait` call
- **Path:** Each `awaitNoteLoaded` first awaits any pending save for the
  same id. With K parallel awaits during view-directive load, K serialized
  awaits on the cut-paste mutex.
- **Cost:** Bounded by save throughput; mostly a no-op if no save is in
  flight.
- **Severity:** **Low.** Saves are infrequent enough that this is
  unobservable in practice.

### `viewSessions` snapshot writes — coalesced (was O(N))
- **Path:** Android `InlineEditState.viewSessions` — `mutableStateMapOf`.
  Each `viewSessions[id] = session` triggers a Compose snapshot write.
- **Status:** **Mitigated.** `ensureSessionsForNotes` now wraps all writes
  in `Snapshot.withMutableSnapshot { ... }` so K notes publish as one
  recomposition. Web's plain `Map` doesn't have this issue (re-render is
  driven by an explicit `setReadyTick`).

---

## Firestore reads / writes

### Per-line document model — every line is a doc (architectural)
- **Path:** Save writes 1+N docs per note (root + descendants). Read does
  the same on cold cache.
- **Cost:** Multiplies note count by ~average-lines-per-note for billing
  and snapshot size.
- **Severity:** **High by design.** Already foundational
  (per CLAUDE.md `Every line is a Firestore document`). Documented;
  optimization paths (batch under a single doc, defer empty-line writes)
  are off the table for now.

### `loadNoteLinesAwait` Firestore fallback — N round trips on listener stall
- **Path:** When `awaitNoteLoaded` times out (1500ms), each waiting note
  falls through to its own `loadNoteWithChildren` (one Firestore doc-get +
  one collection-group query for descendants).
- **Cost:** With K parallel timeouts during a sluggish snapshot delivery,
  K round trips with no batching.
- **Severity:** **Low.** Bounded by timeout (single-shot, not retried).
- **Fix sketch:** when multiple awaits time out near-simultaneously, batch
  via a `documentId() in [...]` query. Worth doing only if logs show this
  fires.

### `saveAll` 500-op batch chunking — handled
- **Path:** `commitInBatches` chunks per-batch ops at the Firestore 500-op
  limit.
- **Status:** **Already handled.** No scaling risk.

### Save planner reads — all from in-memory NoteStore
- **Path:** `planSave` doesn't issue Firestore reads. All descendant lookup,
  parent walks, and remote-base reads come from `rawNotes`.
- **Status:** **Architectural win.** A pre-Phase-2 save issued a separate
  query to fetch descendants per save; that's gone.

---

## Editor / Compose

### `useEditor.ts` clean-load duplicates the `loadNoteLinesAwait` pattern
- **Path:** Lines 226–260 hand-roll the same three-step sequence
  (`awaitPendingSave` → `getRawNoteById` + `getNoteLinesById` →
  `loadNoteWithChildren`) that `loadNoteLinesAwait` now centralizes.
- **Cost:** Code duplication, not a runtime cost.
- **Severity:** **Low.** Filed as a future cleanup; both paths await the
  same NoteStore primitive, so behavior is consistent.

### `ViewDirectiveRenderer.tsx` `useEffect` re-fires per render
- **Path:** Effect deps `[notes, sessionManager]`. `notes` comes from
  `viewVal.notes` and is rebuilt on every directive recompute, so the
  effect re-runs each parent render.
- **Cost:** `ensureSessions` short-circuits cheaply on second call (early
  return if all sessions exist), but the effect still allocates a
  Promise + closure + cleanup each time.
- **Severity:** **Low.** Allocations are tiny. If we ever measure render
  cost, key on `notes.map(n => n.id).join(',')` or a content hash.

---

## What this doc does NOT cover

- Correctness invariants (line identity, soft-delete, 3-way merge) — see
  CLAUDE.md.
- Bug-fix tech debt — see `save-refactor-followups.md`.
- Network errors / offline behavior — see `save-robustness-architecture.md`.
- Listener-cache patterns, instrumentation rules — see
  `firestore-efficiency.md`.

## Process for using this doc

When a /simplify pass surfaces a scaling-shaped concern that isn't worth
fixing now, add it here with the dimension that scales (M / N / K /
snapshots) and a fix sketch. When workspace tooling lands that lets us
measure real-world M/N/K, prioritize from this list using actual data
rather than gut feel.
