# EditorController Parity (Android ↔ Web)

Both platforms ship parallel `EditorController` implementations:

- **Android:** `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/EditorController.kt`
- **Web:** `web/src/editor/EditorController.ts`

This doc tracks the public/internal API of each so cross-platform fixes can mirror cleanly.
**When you change one side, update the other and revise this doc.**

The two are intentionally kept signature-compatible: same method names, same arg
order, same return semantics. Internal helpers may differ in idiom (Kotlin
class-private vs TS module-level functions) but their inputs and outputs match.

## Method-by-method

| Method | Status | Notes |
|---|---|---|
| `findVisibleNeighbor(fromIndex, direction)` | mirrored | Walks past `hiddenIndices`. |
| `canUndo` / `canRedo` | mirrored | Property/getter delegating to `undoManager`. |
| `commitUndoState(continueEditing)` | mirrored | |
| `undo()` / `redo()` | mirrored | Returns `UndoSnapshot?` / `\| null`. |
| `resetUndoHistory()` | mirrored | |
| `recordAlarmCreation(alarm)` | **android-only** | Alarms are an Android subsystem; web has no alarm UI. |
| `updateLastUndoAlarmId(newId)` | **android-only** | Same reason as above. |
| `toggleBullet()` | mirrored | |
| `toggleCheckbox()` | mirrored | |
| `indent()` / `unindent()` | mirrored | |
| `paste(plainText, html?)` | mirrored | Both delegate to a shared `PasteHandler` / `executePaste`. |
| `cutSelection()` | **divergent signature** | Android takes `ClipboardManager`; web uses `navigator.clipboard` directly. Same return semantics (`String?` / `string \| null`). |
| `copySelection()` | **divergent signature** | Same divergence as `cutSelection`. Android returns `Unit`; web returns `void`. |
| `deleteSelectionWithUndo()` | mirrored | |
| `insertAtEndOfCurrentLine(text)` | mirrored | |
| `confirmDirectiveEdit(lineIndex, startOffset, endOffset, newText)` | mirrored | |
| `getMoveUpState()` / `getMoveDownState()` | mirrored | Returns `MoveButtonState`. |
| `moveUp()` / `moveDown()` | mirrored | Returns `Boolean` / `boolean`. |
| `sortCompletedToBottom()` | mirrored | Returns `Boolean` / `boolean`. |
| `moveSelectionTo(targetGlobalOffset)` | **web-only** | Mouse drag-and-drop of a selection. Android uses touch interaction; no equivalent. |
| `clearSelection()` | mirrored | |
| `insertText(lineIndex, text)` | mirrored | |
| `deleteBackward(lineIndex)` / `deleteForward(lineIndex)` | mirrored | |
| `splitLine(lineIndex)` | mirrored | |
| `mergeToPreviousLine(lineIndex, targetIndex?)` | mirrored | |
| `mergeNextLine(lineIndex, targetIndex?)` | mirrored | |
| `setCursor(lineIndex, position)` | mirrored | |
| `setContentCursor(lineIndex, contentPosition)` | **android-only** | Convenience wrapper used by Android IME/touch plumbing; web callers compute the offset inline. |
| `isContentOffsetInSelection(lineIndex, contentPosition)` | **android-only** | Tap hit-test for selection; web does this in mouse-handler hooks. |
| `setCursorFromGlobalOffset(globalOffset)` | mirrored | |
| `updateLineContent(lineIndex, newContent, contentCursor)` | mirrored | Used by IME/onChange path. Body decomposed into private helpers `splitLineOnNewline`, `applyContent`, and (Android-only) `stampNoteIdSentinelIfNeeded`. |
| `focusLine(lineIndex)` | mirrored | |
| `hasSelection()` | mirrored | Method on Android, getter on web. |
| `setSelection(start, end)` | mirrored | |
| `setSelectionInLine(lineIndex, contentStart, contentEnd)` | mirrored | |
| `extendSelectionTo(globalOffset)` | **web-only** | Thin wrapper over `state.extendSelectionTo`; Android callers reach `state` directly via `internal` access. |
| `handleSpaceWithSelection()` | mirrored | |
| `replaceSelectionNoUndo(text)` | **android-only** | `internal` on Android, exposed for IME. Web has no equivalent caller. |
| `deleteSelectionNoUndo()` | **android-only** | Same as above. |
| `toggleCheckboxOnLine(lineIndex)` | mirrored | |
| `getLineText(lineIndex)` | mirrored | |
| `getLineContent(lineIndex)` | mirrored | |
| `getContentCursor(lineIndex)` | mirrored | |
| `getLineCursor(lineIndex)` | mirrored | |
| `isValidLine(lineIndex)` | mirrored | |

## Documented divergences

These are the only places the two implementations diverge by design — keep them
in sync only as platform conventions evolve.

1. **Clipboard wiring.** Android's `cutSelection` / `copySelection` take a
   `ClipboardManager`; web reaches `navigator.clipboard` directly. The body
   logic is otherwise identical. Don't try to share — the platform APIs are
   incompatible at the type level.

2. **Selection drag-to-move (`moveSelectionTo`).** Web-only. The web editor
   supports mouse drag-and-drop reordering of a selection; Android's touch UI
   does not surface this gesture. If Android ever adds a stylus/long-press
   drag, port the web implementation directly — it composes `deleteSelection`
   and `executePaste` and is platform-agnostic in its core.

3. **Alarm undo hooks (`recordAlarmCreation`, `updateLastUndoAlarmId`).**
   Android-only. Alarms are scheduled as Android `AlarmManager` resources and
   need redo support that recreates them with new IDs. Web does not currently
   schedule platform alarms.

4. **IME plumbing helpers (`setContentCursor`, `isContentOffsetInSelection`,
   `replaceSelectionNoUndo`, `deleteSelectionNoUndo`).** Android-only. These
   exist to bridge the Compose IME pipeline; web's textarea/onChange model
   doesn't need them.

5. **`extendSelectionTo`.** Web-only as a controller method, but it's only a
   one-line passthrough to `EditorState.extendSelectionTo`. Android callers
   call the state method directly via `internal` visibility.

6. **`mergeNoteIds` / `buildMergedContentLengths`.** Same algorithm, different
   scoping. Kotlin: `private fun` on the class. TypeScript: module-level
   `function` in the same file. The bodies must mirror — both handle
   sentinel-vs-real-id collapsing identically.

7. **`stampNoteIdSentinelIfNeeded`.** Android-only private helper called from
   `updateLineContent`. Stamps a TYPED sentinel on a non-root line that has
   non-empty content but no id yet — defends against an Activity ON_STOP
   (rotation) firing a save before the user presses Enter. Web has no
   equivalent lifecycle event, so the helper does not exist there.

## Where shared logic actually lives

The controller is a thin orchestrator. The substantive logic lives in
algorithm files that each platform reimplements:

| Concern | Android | Web |
|---|---|---|
| Paste structure | `util/PasteHandler.kt` | `editor/PasteHandler.ts` |
| Clipboard parsing | `util/ClipboardParser.kt` | `editor/ClipboardParser.ts` |
| Move-target finding | `move/MoveTargetFinder.kt` | `editor/EditorState.ts` (inline) |
| Completed-line sort | `util/CompletedLineUtils.kt` | `editor/CompletedLineUtils.ts` |
| Note-id splitting on edits | `data/ContentSimilarity.kt` | `editor/ContentSimilarity.ts` |
| Line prefix detection | `util/LinePrefixes.kt` | `editor/LinePrefixes.ts` |

When changing paste/move/sort/split behavior, edit *both* the controller
wrapper and the corresponding algorithm file on each platform.

## Not in the controller

- **Keyboard navigation** (arrow keys, shift-select). Lives in
  `web/src/hooks/useEditorLineKeyboard.ts` on web and inside
  `ime/DirectiveAwareLineInput.kt` (and helpers) on Android. The controller
  exposes the primitives (`setCursor`, `extendSelectionTo`, `findVisibleNeighbor`)
  that these layers compose.
- **Mouse / touch hit-testing.** Web: `editor/TextMeasure.ts` + the
  `useEditorLineMouse` hook. Android: gesture handlers in `ime/`.
- **Undo/redo history machinery.** Lives in `UndoManager` on each platform;
  the controller only exposes `undo()`, `redo()`, `commitUndoState()`,
  `resetUndoHistory()`.
