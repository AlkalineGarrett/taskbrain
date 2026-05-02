# Save-refactor follow-ups

Deferred items, known limitations, and follow-up work from Phases 0–9 of the
save-path refactor (plus the cleanup pass that followed). Review after the
plan completes; not all of these are worth doing.

Organized by phase, with priority hints:
- **High** — real correctness/UX gap or load-bearing tech debt
- **Medium** — tech-debt cleanup or symmetry fixes worth a focused pass
- **Low** — style/cosmetic; only worth touching if you're already in the area

## Cross-cutting

- **Move-vs-no-move cross-note conflicts.** If client A reparents `c2` to a
  different note while client B keeps `c2` as a child of the original parent
  (no edit), Phase 4's merge can leave `c2`'s `parentNoteId` and the parents'
  `containedNotes` mutually inconsistent. Last-writer-wins on the descendant
  write. **Low** (rare; users rarely move-conflict).

- **Document the remaining null-synthesis path** in
  `NoteStore.getNoteLinesByIdOrSynthesize` (Android). Used by `InlineEditSession`
  on Android — needs an async refactor to fully remove. The Phase 0 fix
  closed the `CurrentNoteViewModel.loadContent` path; this one stays. **Low.**

## Phase 1 (schema)

- **Verbose Note doc comments.** Both platforms duplicate the schema doc at
  field level + in `docs/schema.md`. Field-level docs add IDE-tooltip value;
  acceptable, but watch for drift. **Low.**

## Phase 2 (atomic batched save)

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

## Phase 4 (3-way merge of `containedNotes`)

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

- **Restore wizard for cuts whose source was deleted.** Phase 6's restore
  flips `state=null` and relies on stray-child reconstruction to re-attach
  under the original `parentNoteId`. If the parent has since been deleted,
  the restored doc orphans (it'd then surface in the orphans cluster).
  Workable but not seamless. **Low.**

## Phase 8 (done — kept here for context)

- **`updateFromText` migration and deletion** — completed. ~340 mechanical
  call-site replacements moved tests to a new `EditorState.initFromText`
  test helper (in `EditorStateTestHelpers.kt`); 4 redundant reconciliation
  tests in `NoteIdPropagationTest` were dropped (covered by
  `LineReconciliationTest`); production `updateFromText` is gone.

## Phase 9 (done — kept here for context)

- **Per-descendant 3-way merge for `containedNotes`** — completed. Phase 4's
  merge now runs uniformly at every depth instead of just the root.
  - `NoteStore.snapshotLocalBases(rootId)` captures `containedNotes` for
    the root + every live descendant in a single map (keyed by id).
  - `SaveItem.localBases: Map<id, base>` replaces the previous
    `localBase + descendantBases` split — root and descendants travel as
    one anchor; null on legacy paths (RecoverScreen, etc.).
  - `findConcurrentSubtree` takes the same map and BFS-expands seeds drawn
    from every anchored parent — concurrent additions another client made
    under any depth are kept alive instead of soft-deleted by our save.
  - The merge loop runs once over `childrenOfLine`, producing a uniform
    `mergedContained: string[][]` that the root write and the descendant
    write loop both consume. Sentinel (new) lines fall through to the
    local list. `containedNotesBase` is stamped per-doc whenever an
    anchor exists for that id.
  - Captured + refreshed in `useEditor` / `InlineEditSession` (web) and
    `CurrentNoteViewModel` / `InlineEditSession` (Android), threaded
    through `useSaveCoordinator` (web) / `saveAll` (Android) as a single
    field per layer.

A few observations from the cleanup pass that became their own follow-ups:

- **`snapshotLocalBases` is O(total live notes) per call.** Every edit-session
  start and every successful save calls it; `getDescendantIds` scans the
  whole `rawNotes` map. For workspaces with M total notes and a root with N
  descendants, that's O(M) + O(N) per save. A precomputed `id → descendantIds`
  index on `NoteStore` (invalidated on listener delta) would reduce both
  this and `findConcurrentSubtree`'s BFS-index rebuild. **Low** until
  profiling flags it on a real workspace.

## Cleanup pass (after Phase 8 — done, kept for context)

The following items from earlier sections were resolved:

- **Phase 1**: Note.ts type-style migration (`?: T | null` → required), nullTrace allocation parity (web now lazy).
- **Phase 2**: Group B state-literal sweep (now `NoteState.DELETED`/`CUT_DELETE` everywhere except tests verifying the on-wire format).
- **Phase 3**: `persistHandler` → `mainHandler` rename on Android.
- **Phase 4**: `editorState` dropped from `useSaveCoordinator` interface; `findConcurrentSubtree` API symmetry.
- **Phase 5**: `planSave` parameter sprawl bundled into `SaveContext`.
- **Phase 6**: `alert()` in RecoverScreen replaced with in-page error banner.
- **Phase 7**: stringly-typed `state` literals at remaining read/write sites swept to `NoteState` constants (including the soft-delete write payloads, the `hardDeleteAllSoftDeleted` query, and `filterTopLevelNotes` `!= "deleted"` that pre-dated even Phase 1's Group A migration).

A few observations from the cleanup pass that became their own follow-ups:

- **`window.alert()` still called from `CommandBar.tsx:59` and
  `useEditorLineKeyboard.ts:129`** for paste failures — same pattern as
  the RecoverScreen call replaced in this pass. A shared `<ErrorBanner>`
  component would dedupe these and the existing
  `saveErrorBanner`/`saveErrorDismiss` styles in `NoteEditorScreen.module.css`
  + `RecoverScreen.module.css`. **Low.**

- **`SaveContext` interface/data class doc comment is byte-identical
  across web and Android.** Cross-platform parity by design (per
  CLAUDE.md), but worth watching for drift. **Low.**

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
