# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TaskBrain is a cross-platform ADHD task management app with a Firebase backend and Gemini AI integration. It has two clients:
- **Android** (Kotlin, Jetpack Compose) — the primary app in `app/`
- **Web** (TypeScript, React) — in `web/`

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew test                 # Run unit tests
./gradlew connectedAndroidTest # Run instrumentation tests
./gradlew clean                # Clean build
```

Run a single test class:
```bash
./gradlew test --tests "org.alkaline.taskbrain.data.NoteLineTrackerTest"
```

## Architecture

**Pattern:** MVVM with Jetpack Compose UI

**Key directories:**
- `app/src/main/java/org/alkaline/taskbrain/data/` - Data layer (models, repository, utilities)
- `app/src/main/java/org/alkaline/taskbrain/ui/` - UI layer (screens, viewmodels, components)
- `app/src/test/` - Unit tests using JUnit 4 + MockK

**Data flow:** Compose Screens → ViewModels → NoteRepository → Firebase Firestore

## Core Components

**NoteRepository** (`data/NoteRepository.kt`): All Firestore operations. Uses transactions for atomic updates and Result<T> for error handling.

**NoteLineTracker** (`data/NoteLineTracker.kt`): Maintains stable note IDs across edits. Two-phase matching: exact content match first, then positional fallback. Critical for preserving parent-child relationships when lines are reordered.

**PrompterAgent** (`data/PrompterAgent.kt`): Wraps Gemini AI (gemini-2.5-flash) for note enhancement.

## Important Patterns

**Every line is a Firestore document:**
- Each editor line — including empty lines — round-trips as its own Firestore doc
- No auto-appended trailing-empty UI line; no trailing-empty stripping on save
- New empty lines get a TYPED/SPLIT sentinel noteId at edit time so save can allocate fresh docs for them

**Note structure in Firestore:**
- First line = parent note content
- Additional lines = contained child notes (via `containedNotes` array, ordered list of real document IDs only)
- An empty-string entry (`""`) in `containedNotes` is data corruption — `reconstructNoteLines` logs it at error level and drops it
- Deletion is soft (state = "deleted")

**Whitespace semantics:**
- Empty content string is a real, persisted, addressable line
- Whitespace-only content ("   ") is also content — same as any other line

## Testing

Tests use MockK for mocking and `runTest` for coroutines. Key test files:
- `NoteLineTrackerTest.kt` - Line ID preservation logic
- `NoteRepositoryTest.kt` - Repository operations with mocked Firestore

## Dependencies

Versions managed in `gradle/libs.versions.toml`. Key stack:
- Kotlin 2.1.0, Compose, Material 3
- Firebase (Auth, Firestore, AI)
- Google Sign-In with Credential Manager

## Release

See `TODO_RELEASE.md` for signing configuration. Requires environment variables: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

## Development guidance

### Project requirements

Consult the following files to understand project requirements:

- .md files under docs/
- files name requirements.md in the source tree

### Undo/Redo Considerations

**Architecture**: All discrete editing operations must flow through `EditorController`, which handles undo boundary management via an operation-based system:

1. **EditorController** is the single channel for state modifications (mutation methods on `EditorState` are `internal`)
2. **OperationType enum** classifies operations: `COMMAND_BULLET`, `COMMAND_CHECKBOX`, `COMMAND_INDENT`, `PASTE`, `CUT`, `DELETE_SELECTION`, `CHECKBOX_TOGGLE`, `ALARM_SYMBOL`
3. **Operation executor** (`executeOperation`) wraps operations with proper pre/post undo handling

**When adding new operations that modify editor content:**
- Add a new `OperationType` if it has distinct undo semantics
- Add a method to `EditorController` that wraps the operation with `executeOperation()`
- Call the controller method from UI code (never call `EditorState` mutation methods directly)

**Key questions to ask the developer:**
- Should this operation create its own undo boundary, or be grouped with adjacent edits?
- For command bar buttons: should consecutive presses be grouped (like indent) or separate (like bullet)?
- If the operation creates side effects (like alarms), should those be undoable?
- Does the operation need special handling for redo (e.g., recreating external resources)?

See `ui/currentnote/requirements.md` for full undo/redo specification and implementation details.

### Error Handling and User Feedback

The app should generally inform users when problems occur rather than silently failing or recovering:
- **Show warning dialogs** when operations fail or produce unexpected results, even if the app can recover automatically
- **Explain what happened** in user-friendly terms, including what automatic recovery was attempted
- **Indicate potential inconsistency** if recovery may have left things in a partial state (e.g., "Consider saving and reloading")
- Use `AlertDialog` for warnings; see existing patterns in `CurrentNoteScreen.kt` (e.g., `redoRollbackWarning`, `schedulingWarning`)

This principle applies especially to:
- Failed async operations (Firebase, alarms, etc.)
- Automatic rollbacks or cleanup after failures
- Permission issues that affect functionality

**Never silently swallow errors:**
- Do not use empty `catch` blocks, `/* ignore */` comments, or `.getOrNull()` without handling the failure case
- Every `.onFailure` / `catch` must either propagate the error to a caller that surfaces it, or surface it directly
- In ViewModels: set error state (e.g., `_alarmError.value`) that the UI observes
- In BroadcastReceivers / background services: use `AlarmErrorActivity.show(context, title, message)` to display errors
- Logging (`Log.e`) alone is not sufficient — errors must be user-visible

### Cross-Platform Consistency (Android ↔ Web)

The Android and web apps share the same Firebase backend and should present a consistent UI. Two pairs of files are the source of truth for keeping them in sync:

**Colors:**
- Android: `app/src/main/res/values/colors.xml` (defines titlebar, action, and theme colors)
- Web: `web/src/index.css` `:root` block (CSS custom properties with comments mapping to Android names)
- All web CSS modules use `var(--color-*)` — never hardcode hex colors in `.module.css` files

**Strings:**
- Android: `app/src/main/res/values/strings.xml` (all user-facing text)
- Web: `web/src/strings.ts` (all user-facing text, with comments mapping to Android resource names)
- Android composables use `stringResource(R.string.*)` — never hardcode user-facing text in Kotlin UI code
- Web components import from `@/strings` — never hardcode user-facing text in TSX files

**When adding new UI text or colors:**
1. Add the string/color to the Android resource file first (it's the canonical source)
2. Add the corresponding entry to the web file (`strings.ts` or `index.css`)
3. Reference via `stringResource()` / `var(--color-*)` / imported constant — never inline

**When modifying existing text or colors:**
1. Update both the Android resource file and the web counterpart
2. Search for any remaining hardcoded instances on both platforms

### Live Cross-Platform Sync

Both platforms sync note changes in real time via Firestore snapshot listeners. The architecture has three layers with distinct responsibilities:

**NoteStore (data layer):** Always reflects Firestore truth. No filtering — `rawNotes` updated, `_notes` rebuilt, `changedNoteIds` emitted for every incremental snapshot. The NoteStore does NOT suppress or delay any changes. There is no hot/cool mechanism.

**Editor (UI layer):** Decides whether to reload based on guards:
- `dirty` flag (Android) / `dirtyRef` (web): skip reload while user has unsaved edits
- `savingRef` (web): skip during save-in-progress
- Content equality: skip when Firestore echo matches current content (natural echo suppression)

**Directive cache (view directives):** Two invalidation mechanisms work together when notes change:
1. `invalidateForChangedNotes(changedIds)`: eagerly clears cache entries for the changed notes themselves (per-note)
2. `bumpDirectiveCacheGeneration()`: forces `computeDirectiveResults` to re-run, where the `StalenessChecker` detects cross-note staleness (e.g., note A's `[view B]` directive is stale because B changed)

**Critical invariant:** The generation bump MUST be unconditional. Do not gate it on whether `invalidateForChangedNotes` found entries to clear. Per-note clearing only handles direct entries; cross-note dependencies (view directives referencing other notes) rely on staleness checks at lookup time, which only run when `computeDirectiveResults` re-executes via the generation bump.

**Embedded note editors (view directives):** Must update content in place on the same `EditorState` instance — never recreate the session on external changes. Session recreation orphans the Android IME connection, causing the focused line to render empty text. Use `initFromNoteLines(preserveCursor=true)` on the existing session, matching the main editor's pattern.

**Edit session lifecycle:** The `EditSessionManager` suppresses staleness checking for the originating note during inline editing. The inline save success path MUST call `endInlineEditSession()` to lift this suppression — otherwise subsequent external changes are permanently blocked from reaching the directive cache.

### Refactoring

- Do the following when refactoring:
  - Look to make the code and design "elegant"
  - Consolidate repetition of code
  - Consolidate repetition of patterns based on the same concept
    - If the same groupings of parameters are passed around in multiple places, encapsulate them
  - Break apart long functions (anything longer than 50 lines is suspicious; the more indented, the more it needs to be broken apart)
  - Break apart long files
  - Avoid deeply nested code (anything 4 or more levels deep is suspicious, especially the more lines of code it is)
  - Make sure each unit (file, function, class) has a clear responsibility and not multiple, and at a single level of granularity (think: don't combine paragraph work with character work)
  - Define constants or config instead of hard-coded numbers
  - Move logic out of display classes
  - Look for ways that different use cases have slightly different logic, when there is no inherent reason for them to be different, and merge them
  - Decouple units by using callbacks, etc so that classes refer to each other directly less often
  - Look for places with too many edge cases and come up with more robust, general logic instead
