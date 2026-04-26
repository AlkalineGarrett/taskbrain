# Refactoring Backlog

Master list of code-quality issues identified in the codebase audit (2026-04-26). Work top-to-bottom; check items off as they land. Each entry cites file paths so the work can be picked up without re-discovering context.

## High severity

- [x] **1. `web/src/screens/NoteEditorScreen.tsx` — 660-line screen mixing concerns.** _(done; 693 → 258 lines)_
  Extracted 9 hooks under `web/src/hooks/`: `useNoteDeletion`, `useTabSync`, `useDirectiveMutations`, `useCompletedLineDisplay`, `useActiveEditorSession`, `useUnifiedUndo`, `useSaveCoordinator`, `useGlobalKeyboardShortcuts`, `useGutterRouting`. Screen body is now hook composition + JSX. All 1536 web tests pass.

- [x] **2. `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/CurrentNoteScreen.kt` — 1316-line composable.** _(done; 1316 → 1068 total lines, main `CurrentNoteScreen` body 803 → ~530 lines)_
  Extracted 6 helpers under `ui/currentnote/`: `AlarmDialogState`, `DisplayedNoteIdState`, `EditorContentState`, `DirectiveResultsState`, `UnifiedUndoState`, `NoteEditorBody`. Added `EditorState.initFromNoteLines(List<NoteLine>, …)` overload to consolidate 6 inline-mapped call sites (screen, InlineEditSession ×2, DirectiveAwareLineInput ×2). Fixed a preexisting recurring no-op write to `controller.hiddenIndices` by wrapping in `SideEffect`. Two unit-test files for `AlarmDialogState` and the unified-undo context-id helpers.

- [x] **3. `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/ime/DirectiveAwareLineInput.kt` — 1502 lines mixing composition, custom drawing, text measurement, and IME coordination.** _(done; 1497 → 292 lines for the main composable)_
  Extracted 6 sibling files in the same `ime/` package: `DirectiveStyles.kt` (14), `DirectiveCursorMapping.kt` (59), `DirectiveOverlayText.kt` (310), `ViewDirectiveContent.kt` (299), `InlineNoteEditor.kt` (333), `ButtonDirective.kt` (126). Cross-file consolidation: `mapSourceToDisplayCursor` / `mapDisplayToSourceCursor` were dupes of `mapSourceToDisplayOffset` (in `dsl/directives/DirectiveSegment.kt`) and a private `mapDisplayToSourceOffset` in `gestures/EditorGestureHandler.kt`; promoted both directions to canonical `DirectiveSegment.kt` and removed duplicates. Simplify pass also fixed: a real memory leak (`viewLineLayouts`/`viewGutterStates` now cleaned up via `DisposableEffect`), pre-computed directive geometry once per layout (was called twice per render in draw + tap-target loops), dropped an unused `focusRequester.requestFocus()` block on an unattached requester, deleted dead `renderContentWithDirectives`, removed unused `hostView`/`focusRequester`/`displayContent` params, collapsed single-vs-multi note branches, tightened `internal` constants to `private` where single-file. Added `CursorMappingTest.kt` (13 cases) for the cursor-mapping helpers.

- [ ] **4. Cross-platform `EditorController` duplication (Android 1263 / web 929 lines).**
  Paste/cut/indent/move logic re-implemented in parallel. Decision needed: document divergence explicitly, OR model operations as `EditOperation` classes with parallel signatures so fixes mirror cleanly. Discuss before acting.

## Medium severity

- [x] **5. `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/components/AlarmConfigDialog.kt` — 810 lines, 12 state vars in the composable, mode-dependent active state, inline validation.** _(done; 810 → 386 for the dialog, three new sibling files totalling 557 more lines for a coherent split)_
  Extracted to `AlarmConfigState.kt` (201) + `AlarmStageRow.kt` (243) + `AlarmStatusButtons.kt` (113). The state holder collapses 8 `mutableStateOf` vars into one class with `private set` fields and active-mode helpers (`setActiveDueTime`, `updateActiveStage`, `setCrossPropChecked`, `crossPropLabelRes`); `dispatchSave` consolidates the inline 4-arm save `when`. Skipped: a sealed `AlarmEditState` (would lose mode-toggle state preservation) and "move SaveState into ViewModel" (would change the public dialog API and is outside the file's scope). All tests pass.

- [x] **6. `web/src/components/EditorLine.tsx` — 683 lines; selection-rect math, ResizeObserver, DOM queries, and drag handlers entangled.** _(done; 679 → 245 lines)_
  Extracted 4 hooks under `web/src/hooks/`: `useSelectionRects` (111), `useTextareaAutoResize` (30), `useEditorLineKeyboard` (301), `useEditorLineMouse` (125). Simplify pass also added a `findVisibleNeighbor(fromIndex, direction)` method to `EditorController` and replaced 8 inline `while (target ≥ 0 && hiddenIndices.has(target)) target--/++` walks (6 in the new keyboard hook, 2 pre-existing in `deleteBackward`/`deleteForward`); moved `composingRef` into the keyboard hook; updated the test to import `shouldSyncCursorForKey` from `@/hooks/useEditorLineKeyboard` and dropped the EditorLine.tsx re-export. All 1596 web tests pass.

- [ ] **7. `EditorController.kt:983-1060` `updateLineContent` — 77-line method with deep conditional state updates.**
  Candidate for `EditOperation` decomposition (relates to #4).

## Lower severity

- [ ] **8. `EditorState` (Android 850 / web 523 lines) — 40+ mutator helpers; invariants hard to track.**
  Push toward immutable `LineOperation` results; make `EditorState` more of a query object.

- [ ] **9. `Parser.kt` (718) / `Parser.ts` (511) — monolithic with 20+ private helpers, no phase separation.**
  Split into `TokenStream` → `ExpressionParser` → `StatementParser`.

- [x] **10. `DirectiveAwareLineInput.kt:145-166` — hardcoded `2.dp`, `16.dp`, `24.dp`, `32.dp`, stroke multipliers.** _(obsoleted by #3)_
  Item #3 split the file into 6 sibling files; the once-inline magic numbers now live as named `internal`/`private val` constants in `DirectiveStyles.kt` (`CursorWidth`, `ViewEditButtonSize`, `ViewEditIconSize`), `ButtonDirective.kt` (`ButtonMinHeight`, `ButtonCornerRadius`, `ButtonIconSize`), and `DirectiveOverlayText.kt` (`DirectiveBoxStyle.{strokeWidth, dashLength, gapLength, cornerRadius, padding}`, `EmptyDirectiveTapWidth`). Moving these to `Dimens.kt` (which scopes app-chrome dimensions like top-bar/status-bar) would lose the per-component grouping; remaining inline paddings (`4.dp`, `6.dp`, `8.dp`) are typical Compose layout values not worth abstracting.

- [x] **11. `NoteRepository` logging wrappers — Android `runCatching` repeats across 13+ methods.** _(done; 1018 → 996 lines for the Android repo)_
  Added a private `ioLogged(op: String, block: suspend CoroutineScope.() -> T): Result<T>` helper consolidating the `runCatching { withContext(Dispatchers.IO) {...} }.onFailure { Log.e(TAG, "Error <verb>", it) }` triad and migrated 13 sites. Two large bodies (`saveNoteWithChildren`, `createMultiLineNote`) use a labeled lambda (`body@{ ... return@body x }`) to keep their existing early-exit shape; the rest were rewritten to expression form. Removed an unused `descendantsByRoot` derivation surfaced during the rewrite. The original premise — "web's `logged()` swallows errors silently" — was inaccurate; the web helper already rethrows. Both platforms now log the same `<op> failed` shape. All Android tests pass.

- [x] **13. Source-space char-index resolver duplication (web).** _(done)_ Surfaced during item #6 simplify pass.
  Extracted `getSourceCharOffsetInLine(overlay, directive, textarea, resolveSegments, fallback, x, y)` in `web/src/editor/TextMeasure.ts` — both `useEditorLineMouse.getSourceCharIndex` (per-line) and `hitTestLineFromPoint` (across-lines) now route through it. `getCharOffsetHidingTextarea`/`getCharOffsetFromPoint` are no longer called outside `TextMeasure.ts`. The chip-line path uses a lazy `resolveSegments` thunk so segments are only computed when the directive content is actually hit. `hitTestLineFromPoint` and `positionDropCursorFromPoint` accept an optional `getSegments(lineIndex) => DirectiveSegment[] | null` resolver, and `useEditorInteractions` threads it through — opens the door for view editors / main editor to opt into source-space drag-selection on chip lines (correctness work tracked separately, requires plumbing directive results into the hook callers). All 1596 web tests pass.

- [ ] **12. Time-picker dialog + time formatting duplication.** Surfaced during item #5 simplify pass.
  - `AlarmStageRow.StageTimePicker` is structurally near-identical to the time-picker dialog inside `ui/components/DateTimePicker.kt` (same `Surface`/`TimePicker`/cancel-OK shape). Extract a shared `TimePickerDialog(initialHour, initialMinute, is24Hour, onConfirm, onDismiss)`.
  - `SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", ...)` is repeated in `AlarmStageRow.kt`, `DateTimePicker.kt`, and `service/AlarmUtils.kt`. Extract a `formatTimeOfDay(context, hour, minute)` helper.
  - `MINUTE_MS`/`HOUR_MS` constants duplicated locally in `AlarmStageRow.kt` and conceptually in `RelativeUnit` (`RecurrenceConfigSection.kt`). Centralize.

---

## Notes

- Items #4 and #7 are linked — sequence them together.
- Items #5 and #2 both touch alarm/note UI; doing #5 first is independent and lower-risk.
- After completing each item, mark the checkbox here and note the resulting line counts in the commit message.
