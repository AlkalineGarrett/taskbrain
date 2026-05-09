# Editor (Android)

Editor lives here: state (`EditorState`), the controller (`EditorController`) that owns all mutations, IME wiring (`ime/`), gesture handling (`gestures/`), rendering (`rendering/`), and selection (`selection/`).

## Cross-platform principles (load these too)

@docs/undo-redo-architecture.md
@docs/editor-callback-intent-vs-notification.md
@docs/live-cross-platform-sync.md

## Per-feature requirements

@app/src/main/java/org/alkaline/taskbrain/ui/currentnote/requirements.md

## Android-specific implementation notes

**`markLineFocused(idx)` vs `focusLine(idx)`** — concrete instantiation of the intent-vs-notification rule (see `editor-callback-intent-vs-notification.md`). `markLineFocused` updates `focusedLineIndex` and undo boundary without bumping `focusVersion`; called from `onFocusChanged`. `focusLine` bumps `focusVersion` (firing the focus-grabbing `LaunchedEffect`); call this when programmatically moving focus. Wiring `onFocusChanged → focusLine` is the IME-storm bug — don't.

**Deletion source tracking** — the editor records a per-line `DeletionSource` (`SELECTION_DELETE`, `BACKSPACE_MERGE`, `DELETE_MERGE`, `PASTE_REPLACE`, `MOVE`) at every line-removal site via `recordRemoval`/`recordRemovalForRange`. The save layer reads `controller.consumePendingSoftDeletes()` and stamps a source-tagged `deletionBatchId` on each removed doc. Reset on external content reload (`controller.resetPendingSoftDeletes()` in the `LoadStatus.Success` handler).
