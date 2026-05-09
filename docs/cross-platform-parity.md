# Cross-platform parity (Android ↔ Web)

**The experience should be the same on both platforms.** They share the same Firebase backend, the same user, and (modulo the alarming exception below) the same feature set. When you fix a bug or add a feature on one, port it to the other in the same change — *not later*. Drift is hard to detect after the fact and tends to manifest as one client silently misbehaving against shared data.

## Documented exceptions

- **Alarms** are Android-only. Notification scheduling, the alarm UI, and the recurring-alarm machinery (`AlarmRepository`, `RecurringAlarmRepository`, `AlarmManager`) live only under `app/`. If you find yourself writing alarm code on web, stop and reconsider.

That's the only intentional asymmetry. Anything else divergent is either a bug, a temporary state during a port, or guidance that needs updating.

## Porting workflow

When you change one platform, expect to change the other in the same PR.

1. **Identify the analogue.** The codebases mirror each other:
   - Android `data/NoteRepository.kt` ↔ web `data/NoteRepository.ts`
   - Android `data/NoteStore.kt` ↔ web `data/NoteStore.ts`
   - Android `data/NoteReconstruction.kt` ↔ web `data/NoteReconstruction.ts`
   - Android `data/Note.kt` ↔ web `data/Note.ts`
   - Android `ui/currentnote/EditorController.kt` ↔ web `editor/EditorController.ts`
   - Android `ui/currentnote/EditorState.kt` ↔ web `editor/EditorState.ts`
   - Android `data/DeletionSource.kt` ↔ web `data/DeletionSource.ts`
   - Android `app/src/main/res/values/strings.xml` ↔ web `web/src/strings.ts`
   - Android `app/src/main/res/values/colors.xml` ↔ web `web/src/index.css`
2. **Mirror the change.** Same field, same method, same vocabulary, same default. Don't take shortcuts because "the bug only shows up on Android" — the matching logic on web will rot.
3. **Run both platforms' tests.** `./gradlew test` and `npm --prefix web run test`. If only one was changed, the other's tests should still pass *and exercise the parallel code path*.
4. **Audit before submission** when a change is non-trivial. Ask: "did I update both data classes? both parsers? both reconstruction filters? both save paths? both undelete paths?" The `deletionBatchId` work flushed out a missed `parseNote` field this way — the bug only surfaced on Android because web's parser handled the new field, but Android's hand-rolled one silently dropped it.

## Specific parity surfaces

### User-facing strings

- **Android:** `app/src/main/res/values/strings.xml` (canonical source).
- **Web:** `web/src/strings.ts` (mirrors Android, with comments mapping to the Android resource names).
- Android composables use `stringResource(R.string.*)` — never hardcode user-facing text in Kotlin UI code.
- Web components import from `@/strings` — never hardcode user-facing text in TSX files.

When adding new UI text:
1. Add to the Android resource file first.
2. Add the corresponding entry to `web/src/strings.ts`.
3. Reference via `stringResource()` / imported constant — never inline.

When modifying existing text: update both files; search for any remaining hardcoded instances on both platforms.

### Colors / theme

- **Android:** `app/src/main/res/values/colors.xml` (titlebar, action, theme).
- **Web:** `web/src/index.css` `:root` block (CSS custom properties with comments mapping to Android names).
- All web CSS modules use `var(--color-*)` — never hardcode hex colors in `.module.css` files.

### Note schema

`app/src/main/java/org/alkaline/taskbrain/data/Note.kt` and `web/src/data/Note.ts` are the source of truth for the Firestore document shape. When adding a field:

1. Add to both data classes / interfaces.
2. Update `web/src/data/Note.ts` `noteFromFirestore` so the parser reads it (web uses an explicit field-by-field parser; missing fields silently default).
3. On Android, the snapshot listener (`NoteStore.parseNote`) routes through `doc.toObject(Note::class.java)` and picks up new fields automatically. **Don't reintroduce a hand-rolled `Map<String, Any>` parser** — that's how `deletionBatchId` was silently dropped (only first line + Fix button on deleted notes was the symptom).
4. Update `docs/schema.md`.

### Editor behavior

The two editors are deliberately structured the same way: state object, controller as the single mutation channel, undo manager, paste handler, line state. When you change one:

- **Mutation methods**: same names, same semantics. If Android's `EditorController.moveSelectionTo` does X, web's must do X.
- **Source tagging**: every line-removal site records a `DeletionSource` (`SELECTION_DELETE`, `BACKSPACE_MERGE`, `DELETE_MERGE`, `PASTE_REPLACE`, `MOVE`). Adding a new removal path on one platform means tagging it on both, *and* extending the enum if a new source category is needed.
- **Undo boundary semantics**: see `docs/undo-redo-architecture.md`. Both platforms wrap mutations through `executeOperation`.

### Save / load / sync

- `saveNoteWithChildren` and `saveMultipleNotes` exist on both platforms with the same signature shape. The `deletionSources` parameter is plumbed through both.
- `softDeleteNote` stamps a `whole-note_<uuid>` `deletionBatchId`; `undeleteNote` reads root state authoritatively from Firestore (not the local NoteStore — listener lag races) and restores only same-batch descendants.
- `NoteStore` mirrors Firestore truth on both sides — no filtering, no hot/cool. See `docs/live-cross-platform-sync.md`.

### Reconstruction

`NoteReconstruction` (`*.kt` and `*.ts`) walks `containedNotes` + `parentNoteId` strays the same way. The same `acceptable` predicate decides which children to render based on parent state and `deletionBatchId` matching. `indexChildrenByParent` takes the same optional `includeDeletedBatchId` argument.

### Tests

- Android: `app/src/test/` (Vitest equivalent), `app/src/androidTest/` (instrumentation).
- Web: `web/src/__tests__/` (Vitest), `web/e2e/` (Playwright).

When you fix a bug on one side, the regression test for it should ideally exist on *both* — at minimum the data-layer unit test (most bugs reproduce off the platform-agnostic reconstruction / save logic). UI-level tests can be platform-specific where the UI itself differs.

## Audit pattern

Grep the new symbols / fields / method names against the other platform's source tree. Anything that should be there but isn't is a port gap. When a bug surfaces on one platform that the other "shouldn't" have, it's a strong signal that one of the two has rotted; an audit will tell you which.
