# Cut/paste orphan-ref bug — debug notes

**Status:** Open. Intermittent. Could not reproduce on demand on 2026-04-28.

## Symptom

After a cross-note cut+paste sequence, the source note's "Save" button shows
"Fix". Console logs an orphan ref warning of the form:

```
reconstructNoteLines: dropping orphan ref <CUT_CHILD_ID> from parent <PARENT_ID>
  (missing/deleted/mis-parented). parentContent='<parent content snippet>'
```

The cut child's id is present in the *parent's* `containedNotes` in note A,
but the child itself has been reparented into note B (`parentNoteId !== A's
parent's id`), so reconstruction drops the orphan ref and flips the note
into needs-fix state.

Tapping "Fix" saves note A again with the corrected `containedNotes` and
clears the warning.

## Reproduction steps (only sometimes triggers)

1. In note A, cut a line that is a real child of a non-root parent (i.e. the
   cut child's text starts with a tab so it nests under a parent line above
   it — *not* just a `•`-prefixed sibling at indent 0).
2. Manually save A.
3. Switch to note B and paste.
4. Save B.
5. Switch back to A.

User reported single-line cuts have **never** triggered this. The bug only
appeared after a multi-line cut.

User confirmed (2026-04-28) that the bug did *not* reproduce when the test
notes (`t1`, `t-b`) had all lines at indent 0 (no real tab nesting). Real
tab-indented children seem necessary, though even with proper indents the
user could not deterministically reproduce it on demand.

## What we know

- VRvS (the orphan-warning parent) was a "child of a child of the parent
  note" — i.e. itself nested under a non-root line.
- dGeep (the cut child) was indented one level under VRvS (one tab).
- After the buggy save sequence, dGeep ended up correctly reparented in B
  (under a note `6bzIkOycEDX9VbdgmHkz`), but VRvS in A *still* listed dGeep
  in `containedNotes`.
- Re-saving A cleared the orphan ref. So the underlying data corrects on a
  fresh save — meaning A's editor state at the second save did not include
  dGeep, and `childrenOfLine[VRvS_index]` then computed without dGeep. The
  bug is something specific to what the *first* save wrote (or skipped).

## Hypotheses (untested without a repro log)

Two distinct shapes the bug could take, distinguishable by the
`[debugSave]` log:

1. **Indent-tree miscomputation.** `linesToSave` still claimed dGeep as a
   child of VRvS at save time — i.e. dGeep was somehow in the line array,
   so `childrenOfLine[VRvS_index]` included dGeep. Save A wrote
   `VRvS.containedNotes = [..., dGeep]`. Diagnosis: log shows
   `children=[..., dGeep]` for the VRvS line.
   - Likely culprit: `replaceRangeSurgical` in `EditorState.ts`. The
     `takeLineBIds` branch (`lineA !== lineB && offsetA === 0 && parts[0] ===
     ''`) keeps `lineA` *as a line* but swaps its noteIds for `lineB`'s.
     So an "empty line with the trailing line's id" survives at lineA's
     position. Whether that line stays at the right indent is exactly the
     question — when `offsetA === 0` and `lineA.text` had a tab indent, the
     resulting `newText = '' + '' + suffix` drops the tab. The line might
     end up at a different indent than the user expected, scrambling the
     parent tree.
   - Also: `sortCompletedToBottom` runs at the start of every save and
     reorders lines. A completed line moving across a parent's contiguous
     range would reparent it in the indent tree. Worth checking whether the
     cut child or its siblings had recently been toggled.

2. **descendantSkipped wrongly skipped the parent's update.** `linesToSave`
   correctly excluded dGeep, but the in-store `existing.containedNotes` was
   *also* already missing dGeep (matching `childrenOfLine[VRvS_index]`), so
   `arraysEqual(...)` returned true and the parent's write was skipped —
   leaving Firestore's actual `containedNotes` (still with dGeep) untouched.
   Diagnosis: log shows `children=[...]` *without* dGeep for VRvS but `skip=true`.
   - Likely culprit: the in-memory `rawNotes` Map drifted from Firestore
     truth. We checked: no code path optimistically writes
     `containedNotes` into the store. `noteStore.updateNote` /
     `updateNoteSilently` only touch `reconstructedNotes`, not `rawNotes`.
     But it's worth confirming by checking the listener echo timing of the
     prior save in the queue.

3. **Race/serialization edge case.** The recent commit `255fb79` ("Fixing
   fast cut-copy-paste between note race condition") fixed one race but
   may not cover all paths. Check whether the bug correlates with switching
   away mid-save or with auto-save-on-unmount kicking in alongside the
   manual save.

## Diagnostic flag

A flag-gated save-plan log was added to
`web/src/data/NoteRepository.ts` inside `saveNoteWithChildren`. Enable with:

```js
localStorage.setItem('taskbrainDebugSaves', '1')
```

Disable with:

```js
localStorage.removeItem('taskbrainDebugSaves')
```

When enabled, every save logs:

```
[debugSave] saveNoteWithChildren(<noteId>)
  [i] eid=<id> content=<first 40 chars> parentLine=<i> children=[<ids>] skip=<bool>
  ...
  existingDescendants=[<ids>]
  toDelete=[<ids>]
```

## Next steps when the bug recurs

1. Confirm the flag is on. If not, the bug is unfixable from log evidence
   alone — the `containedNotes` write is gone after the fact.
2. Capture the **full `[debugSave]` block for the source-note save** (the
   save just before switching to the destination note). Also capture the
   destination-note save block for context.
3. Note the orphan parent's id from the warning and find that line's row in
   the source-note save plan. Read off `children=[...]` and `skip=...`.
   - `children` includes the cut child's id → **hypothesis 1** confirmed.
     Investigate `replaceRangeSurgical` and `sortCompletedToBottom`.
   - `children` excludes the cut child's id but `skip=true` →
     **hypothesis 2** confirmed. Investigate why the in-memory
     `rawNotes[parentId].containedNotes` was already stale-equal to the new
     list.
4. If neither shape matches, log shows the parent line wasn't even in
   `linesToSave` (e.g. it was cut too, contradicting the user's claim that
   the parent survived) — re-examine the cut selection.

## Things the user should jot down when it next happens

- How many lines were cut?
- Did the selection start at offset 0 of an indented line, or in the
  middle?
- Did the selection cross indent levels (e.g. start at the parent line and
  extend down into children)?
- Were any of the involved lines checkboxes that had just been toggled
  before the cut? (Triggers `sortCompletedToBottom`.)
- Any other notes open in tabs at the time? Did you switch notes mid-save
  (i.e. before the source-note save's "Saved" indicator returned)?

## Files involved

- `web/src/data/NoteRepository.ts` — `saveNoteWithChildren` (the indent-tree
  build, the descendantSkipped check, the transaction).
- `web/src/data/NoteReconstruction.ts` — `reconstructNoteLines` (where the
  orphan-ref warning is emitted).
- `web/src/data/NoteStore.ts` — the listener-backed cache; `enqueueSave`
  serializes saves but doesn't wait for listener echo.
- `web/src/editor/EditorState.ts` — `replaceRangeSurgical`, the surgical
  multi-line edit path the cut goes through.
- `web/src/editor/EditorController.ts` — `cutSelection` and
  `sortCompletedToBottom`.

## Related (recent)

- Commit `255fb79`: "Fixing fast cut-copy-paste between note race
  condition" — most recent fix in this area; may be incomplete.
- See also: `feedback_save_after_switch_gate.md` (memory) — gates
  post-await mutations on still-same-note AND still-same-content; replaces
  ids by sentinel match, not position.
