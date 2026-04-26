# Refactoring Backlog

Master list of code-quality issues identified in the codebase audit (2026-04-26). Work top-to-bottom; check items off as they land. Each entry cites file paths so the work can be picked up without re-discovering context.

## High severity

- [x] **1. `web/src/screens/NoteEditorScreen.tsx` — 660-line screen mixing concerns.** _(done; 693 → 258 lines)_
  Extracted 9 hooks under `web/src/hooks/`: `useNoteDeletion`, `useTabSync`, `useDirectiveMutations`, `useCompletedLineDisplay`, `useActiveEditorSession`, `useUnifiedUndo`, `useSaveCoordinator`, `useGlobalKeyboardShortcuts`, `useGutterRouting`. Screen body is now hook composition + JSX. All 1536 web tests pass.

- [ ] **2. `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/CurrentNoteScreen.kt` — 1316-line composable, 20+ ViewModel observations, lines 75-224 are pure setup, heavy `LaunchedEffect` chains.**
  Move tab/cache logic into a `rememberEditorCache`; push content-sync `LaunchedEffect`s into the ViewModel; extract sub-composables for header/body/footer regions.

- [ ] **3. `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/ime/DirectiveAwareLineInput.kt` — 1502 lines mixing composition, custom drawing, text measurement, and IME coordination.**
  Split out a `TextMeasurer`, isolate `DrawScope` logic into its own file, decompose into smaller composables.

- [ ] **4. Cross-platform `EditorController` duplication (Android 1263 / web 929 lines).**
  Paste/cut/indent/move logic re-implemented in parallel. Decision needed: document divergence explicitly, OR model operations as `EditOperation` classes with parallel signatures so fixes mirror cleanly. Discuss before acting.

## Medium severity

- [ ] **5. `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/components/AlarmConfigDialog.kt` — 810 lines, 12 state vars in the composable, mode-dependent active state, inline validation.**
  Replace mode/instance/recurrence state vars with a sealed `AlarmEditState = Instance(...) | Recurrence(...)`. Extract `AlarmConfigState` for validation. Move SaveState ops into the ViewModel.

- [ ] **6. `web/src/components/EditorLine.tsx` — 683 lines; selection-rect math, ResizeObserver, DOM queries, and drag handlers entangled (lines 95-190).**
  Extract `SelectionRectManager` and `TextMeasureHelper` utilities.

- [ ] **7. `EditorController.kt:983-1060` `updateLineContent` — 77-line method with deep conditional state updates.**
  Candidate for `EditOperation` decomposition (relates to #4).

## Lower severity

- [ ] **8. `EditorState` (Android 850 / web 523 lines) — 40+ mutator helpers; invariants hard to track.**
  Push toward immutable `LineOperation` results; make `EditorState` more of a query object.

- [ ] **9. `Parser.kt` (718) / `Parser.ts` (511) — monolithic with 20+ private helpers, no phase separation.**
  Split into `TokenStream` → `ExpressionParser` → `StatementParser`.

- [ ] **10. `DirectiveAwareLineInput.kt:145-166` — hardcoded `2.dp`, `16.dp`, `24.dp`, `32.dp`, stroke multipliers.**
  `Dimens.kt` already exists; consolidate values there.

- [ ] **11. `NoteRepository` logging wrappers — Android `runCatching` and web `logged<T>` repeat across 15+ methods; web's `logged()` swallows errors silently.**
  Return typed `Result` from web so callers can distinguish failures. Consider middleware/decorator approach for the wrapper itself.

---

## Notes

- Items #4 and #7 are linked — sequence them together.
- Items #5 and #2 both touch alarm/note UI; doing #5 first is independent and lower-risk.
- After completing each item, mark the checkbox here and note the resulting line counts in the commit message.
