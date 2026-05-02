# Save-refactor follow-ups

Deferred items, known limitations, and follow-up work from Phases 0–8 of the
save-path refactor. Review after the plan completes; not all of these are
worth doing.

Organized by phase, with priority hints:
- **High** — real correctness/UX gap or load-bearing tech debt
- **Medium** — tech-debt cleanup or symmetry fixes worth a focused pass
- **Low** — style/cosmetic; only worth touching if you're already in the area

## Cross-cutting

- **Per-descendant 3-way merge for `containedNotes`.** Phase 4 only merges the
  root note's `containedNotes`. Concurrent edits to a deeply-nested doc's
  child list still last-writer-wins. Realistic but rare conflict. **Low.**

- **Move-vs-no-move cross-note conflicts.** If client A reparents `c2` to a
  different note while client B keeps `c2` as a child of the original parent
  (no edit), Phase 4's merge can leave `c2`'s `parentNoteId` and the parents'
  `containedNotes` mutually inconsistent. Last-writer-wins on the descendant
  write. **Low** (rare; users rarely move-conflict).

- **Document the remaining null-synthesis path** in
  `NoteStore.getNoteLinesByIdOrSynthesize` (Android). Used by `InlineEditSession`
  on Android — needs an async refactor to fully remove. The Phase 0 fix
  closed the `CurrentNoteViewModel.loadContent` path; this one stays. **Low.**

## Phase 8 (done — kept here for context)

- **`updateFromText` migration and deletion** — completed. ~340 mechanical
  call-site replacements moved tests to a new `EditorState.initFromText`
  test helper (in `EditorStateTestHelpers.kt`); 4 redundant reconciliation
  tests in `NoteIdPropagationTest` were dropped (covered by
  `LineReconciliationTest`); production `updateFromText` is gone.

## Phase 1 (schema)

- **`Note.ts` type-style inconsistency.** `version?: number` and
  `lastWriterOpId?: string | null` mix `?` (optional) and `| null` (nullable).
  Picking one form would require updating ~13 inline test fixtures. **Low.**

- **`nullTrace` allocation parity.** Web's `reconcileNullNoteIdsByContent`
  eagerly allocates a `NullIdRecovery[]`; Android's is lazy. Save-time path
  so the cost is negligible, but the asymmetry is a future-bug attractor.
  **Low.**

- **Verbose Note doc comments.** Both platforms duplicate the schema doc at
  field level + in `docs/schema.md`. Field-level docs add IDE-tooltip value;
  acceptable, but watch for drift. **Low.**

## Phase 2 (atomic batched save)

- **Group B `state == "deleted"` sites kept as raw strings.** Phase 1 added
  `isLive(state)` and migrated Group A (live-filtering) sites; Group B sites
  (search-result deleted bucketing, `isDeleted` flag in editor state, etc.)
  specifically asked "is this soft-deleted" rather than "is this not live."
  Phase 6 migrated the RecoverScreen filter; Phase 7 migrated the new
  state-flip helpers; the rest still use raw strings (`NoteSearchUtils`,
  `NoteFilteringUtils`, several editor sites). **Low** (intentional split,
  but worth a quick audit).

- **`extraOpsBuilder` hardcoded `null` in `saveMultipleNotes`.** Multi-note
  batched saves can't carry alarm/extra ops today — only the single-note
  `saveNoteWithChildren` path supports them. If we ever want an inline-session
  save to also create an alarm doc atomically with the main note's save in
  one batch, this needs threading. **Medium** (no live caller hits this
  today, but it's a gap).

## Phase 3 (clientOpId echo suppression)

- **Listener hot-path `Date.now()` per doc.** `isOurEcho` calls `Date.now()`
  inside the doc-change loop; a snapshot with N docs makes N timestamp reads
  + N Map gets. Negligible for typical N (< 50) but worth hoisting `now`
  once per snapshot if a 1000-doc workspace becomes plausible. **Low.**

- **`releasePendingOp` overloads `persistHandler` (Android).** Posts cleanup
  on the persist-debounce handler. Functionally fine, but the handler's
  name implies persistence work. Either rename to `mainHandler` or carve
  out a dedicated handler / `delay` on a coroutine scope. **Low.**

## Phase 4 (3-way merge of `containedNotes`)

- **`editorState` still in `useSaveCoordinator` interface.** Only used at one
  call site (the optimistic store-update text comparison). Could be
  eliminated by moving the update into `prepareMainSaveItem` — but that
  bleeds coordinator concerns into the editor hook. Either kept-as-is or a
  cleaner refactor of the boundary. **Low.**

- **`findConcurrentSubtree` API skew.** Web takes a `getNote` callback
  parameter (testable); Android closes over the `NoteStore` singleton
  (matches existing repo style). Cosmetic; both private. **Low.**

- **Per-descendant `getRawNoteById` in planSave skip-detection.** O(K) lookups
  per save where K is the descendant count. In-memory map; cheap; bounded
  by the 500-op batch limit. **Low** unless profiling flags it.

## Phase 5 (cross-note move via `cut-delete`)

- **Stale cuts beyond a session.** Cuts that survive a page reload before
  paste lose their `pendingCuts` buffer entry (in-memory only). The doc
  remains in `state='cut-delete'` and is recoverable via Recover screen
  (Phase 6), but won't auto-reclaim on a future paste. Mitigation would
  require querying Firestore for cut-delete docs at paste time and matching
  by content. **Medium** (real UX gap; current workaround is "go to
  Recover").

- **Same-content cut collisions.** `tryReclaim` matches by exact `content`.
  Two simultaneous cuts with identical text resolve non-deterministically
  (one paste claims one id, the other falls through to sentinel). Currently
  documented in JSDoc only. **Low** (rare in practice).

- **`planSave` parameter sprawl.** Now takes `(item, userId, opId,
  pendingCuts)` on web and `(noteId, trackedLines, extraOpsBuilder, opId,
  localBase, pendingCuts)` on Android. Bundling into a `SaveContext { userId,
  opId, pendingCuts }` is a clean mechanical refactor. **Low.**

- **EditorController → NoteStore dep.** Phase 5 introduced this import on
  both platforms. The editor is the only producer of cut events and consumer
  of paste-reclaim, so it's the right layer; wrapping in a `CutBuffer`
  interface would be over-engineering at one call site. **Low** (flagged
  for awareness, not action).

- **Pasted-line scan iterates ALL `result.lines`.** The post-paste reclaim
  loop walks the whole document. `executePaste` already knows the
  insertion range; threading `result.insertedRange` through would tighten
  this from O(total document) to O(pasted). **Low.**

- **Hard-delete of stale cut-delete docs.** No purge mechanism for
  cut-delete docs that have been parked indefinitely. User explicitly
  rejected timeout-based operations, so this would have to be a manual
  "Purge parked cuts" UI. **Low.**

## Phase 6 (Recover for parked cuts)

- **`alert()` for error surfacing in RecoverScreen.** Pre-existing pattern;
  Phase 6's new restore handler matches it. CLAUDE.md error-handling
  guidance prefers UI-surfaced errors. **Low.**

- **Restore wizard for cuts whose source was deleted.** Phase 6's restore
  flips `state=null` and relies on stray-child reconstruction to re-attach
  under the original `parentNoteId`. If the parent has since been deleted,
  the restored doc orphans (it'd then surface in the orphans cluster).
  Workable but not seamless. **Low.**

## Phase 7 (state-flip consolidation + Phase 3 contract retrofit)

- **Stringly-typed `state` literals in non-save call sites.** Phase 7
  migrated every state-flip in `NoteRepository` (both platforms) to use
  `NoteState.DELETED` / `NoteState.CUT_DELETE` / `null`, but pre-existing
  reads still compare against raw strings: web `NoteSearchUtils.ts:55`,
  `NoteFilteringUtils.ts:125`, `EditorContentState.kt:90`,
  `CurrentNoteViewModel.kt:214,454`, `loadNoteWithChildren` `isDeleted`
  flags, etc. Mechanical sweep. **Low.**

## Test infra

- **Vitest cwd inconsistency.** Several times during the refactor, running
  `npx vitest run` from the repo root vs `web/` produced spurious failures
  ("Cannot find package '@/editor/...'"). Always `cd web/` first.
  **Low** (workflow note, not a defect).

- **Singleton state leak between Android test classes.** `NoteRepositoryTest`
  uses `mockkObject(NoteStore)`; without `@After unmockkObject` the stubs
  leaked into `NoteStoreTest`. Phase 5 added the `@After` and a
  `clearPendingCutsForTest()` / `clearPendingOpsForTest()` hatch. Worth
  auditing other singletons (`MetadataHasher`, `EditSessionManager` etc.)
  for similar test-isolation hatches. **Low.**
