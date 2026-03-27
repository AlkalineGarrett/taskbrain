# NoteStore Architecture & Lessons Learned

This document captures the architecture, bugs, solutions, and hard-won lessons from the NoteStore rearchitecture (March 2026). It should be read by anyone modifying the note editing, tab switching, view directive, or inline editing systems.

## Architecture Overview

### Single Source of Truth: NoteStore

NoteStore is a singleton that holds all notes with reconstructed content. Both platforms (Android and web) use it as the authoritative source for note content.

- **`reconstructedNotes`** — top-level notes with multi-line content rebuilt from Firestore's tree structure
- **`rawNotes`** — raw Firestore documents, updated by the collection listener
- **Hot/cool mechanism** — prevents Firestore echo from overwriting locally-modified notes

### Content Flow

```
User types → updateContent() → sets userContent + marks note hot (NoteStore.markHot)
                                 (NoteStore is pushed on tab switch, not every keystroke)

Tab switch → SideEffect refs capture previous content → remember(displayedNoteId)
             pushes previous note's content to NoteStore → computeDirectiveResults
             reads NoteStore → view directives see the edit

User saves → saveContent() → NoteStore.updateNote(persist=false) + persistCurrentNote()
             → Firestore write → collection listener → hot note skips rebuild

Inline edit blur → saveInlineNoteContent() → NoteStore.updateNote(persist=false) + Firestore write
```

### Key Design Decisions

1. **Push to NoteStore on tab switch, not every keystroke** — Every-keystroke pushes cause StateFlow emissions that reset the cursor position in the text field. Instead, `updateContent()` just marks the note hot, and a `SideEffect` + `remember(displayedNoteId)` pushes the content synchronously during the composition where the tab actually changes.

2. **Hot/cool prevents Firestore stomping** — When a note is edited, `markHot(noteId)` is called. The collection listener skips rebuilding hot notes from `rawNotes`. After 5 seconds of inactivity, the note "cools down" and the next snapshot applies Firestore truth.

3. **Debounced persist with captured content** — The persist callback captures content at schedule time (not at fire time). This prevents reading stale NoteStore content that might have been overwritten by a collection listener echo.

4. **InlineEditSession is ephemeral** — It reads from NoteStore on creation and writes back only on explicit save/blur. Never during intermediate states.

---

## Bugs Encountered & Solutions

### Bug 1: Stale `currentNoteId` in async callbacks

**Problem:** `currentNoteId` is a mutable field on the ViewModel. Coroutines launched from `saveContent`, `loadContent`, etc. read `currentNoteId` after yielding, but the user may have switched tabs in the meantime.

**Solution:** Every function that launches a coroutine captures `val savedNoteId = currentNoteId` (or `val noteId = currentNoteId`) **before** the `viewModelScope.launch`. The coroutine uses the captured local, never the mutable field.

**Rule:** Never read `currentNoteId` inside a coroutine body. Always capture it as a val first.

### Bug 2: Per-note snapshot listener overwrites

**Problem:** A Firestore snapshot listener on the current note's document would reload content from Firestore, overwriting local edits.

**Solution:** Removed the per-note snapshot listener entirely. NoteStore's collection listener + hot/cool mechanism handles both real-time sync and edit protection.

**Rule:** Don't use per-document snapshot listeners for notes. The collection listener is sufficient.

### Bug 3: Collection listener rebuilds hot notes from stale `rawNotes`

**Problem:** Racing debounced persists could deliver stale content to Firestore. The collection listener would update `rawNotes` with this stale data, then rebuild the note.

**Solution:** Skip rebuild for hot notes (`affectedRoots.removeAll { isHot(it) }`). Always update `rawNotes` (incremental snapshots are not re-delivered if skipped). When the note cools down, the correct persist's echo will have arrived in `rawNotes`.

**Caveat:** Never skip `rawNotes` updates — only skip the rebuild. Firestore incremental changes are delivered once and never repeated.

### Bug 4: Racing debounced persists

**Problem:** Two `saveNoteWithFullContent` calls for the same note could complete out of order, causing the older (stale) content to win in Firestore.

**Solution:**
- Debounce captures content at schedule time (not re-reads from NoteStore at fire time)
- Persist callback cancels any prior in-flight save job for the same note before starting a new one
- `Handler.postDelayed` with explicit `Runnable` tracking (not `postAtTime` with string tokens — `removeCallbacksAndMessages` uses reference equality, not `equals()`)

### Bug 5: `rawNotes` skipping caused permanent data loss

**Problem:** An earlier fix tried to skip `rawNotes` updates for hot notes. But Firestore's `onSnapshot` delivers changes incrementally — once skipped, they're never re-delivered. This left `rawNotes` permanently stale.

**Solution:** Always update `rawNotes`. Only skip the rebuild.

**Rule:** Never skip `rawNotes` updates in `handleIncrementalSnapshot`.

### Bug 6: Re-persist in `handleIncrementalSnapshot` infinite loop

**Problem:** An earlier fix tried to re-persist local content when a stale echo arrived for a hot note. This triggered another Firestore write, another echo, another re-persist — infinite loop flooding Firestore.

**Solution:** Don't re-persist in `handleIncrementalSnapshot`. The original debounced persist is sufficient.

**Rule:** The collection listener should be read-only (update rawNotes, rebuild). Never write to NoteStore or Firestore from the listener.

### Bug 7: `LoadStatus.Success` applied to wrong note

**Problem:** `LoadStatus.Success` didn't carry a noteId. A stale async load result could be applied to the wrong note after a tab switch.

**Solution:** Added `noteId` field to `LoadStatus.Success`. The composable guards: `if (loadStatus.noteId != displayedNoteId) return@LaunchedEffect`.

### Bug 8: `LaunchedEffect` tracking cancelled on fast tab switch

**Problem:** `LaunchedEffect(userContent, isSaved, displayedNoteId)` was used to track dirty content in a map. But `LaunchedEffect` runs after composition — if the user switches tabs in the same frame, the effect is cancelled before it runs.

**Solution:** Replaced with synchronous `remember` block for pushing to NoteStore. `SideEffect` captures latest values after each composition, and `remember(displayedNoteId)` reads the refs (which have previous values) during the tab-switch composition.

**Rule:** Never use `LaunchedEffect` for state that must be captured before a tab switch. Use `SideEffect` refs + `remember(key)` for synchronous capture.

### Bug 9: Composition-time NoteStore mutations

**Problem:** Calling `NoteStore.updateNote` from a `remember` block caused StateFlow emissions during composition, triggering unexpected recompositions and cursor resets.

**Solution:** Don't push to NoteStore on every keystroke. Push only on tab switch (via `SideEffect` refs). During editing, only call `NoteStore.markHot()` (which doesn't emit on StateFlow).

**Rule:** Avoid `NoteStore.updateNote` calls during composition. Use `markHot` for lightweight hot-window extension.

### Bug 10: `getEffectiveDisplayContent` returned stale session content

**Problem:** When a note was edited directly on one tab, then the user switched to a view that had an old `InlineEditSession` for the same note, the rendering used the session's stale content instead of the fresh `displayContent` from the ViewVal.

**Solution:** `getEffectiveDisplayContent` always returns `displayContent`. The session's content is only visible through its own EditorState text field, never through this function.

### Bug 11: `InlineEditSession` not recreated on content change

**Problem:** The `LaunchedEffect(isEditing)` that starts inline edit sessions checked `!hasActiveSession` — if an old session existed, it wasn't recreated even when the note's content changed externally.

**Solution:** Compare `existingSession.originalContent != editContent`. If they differ, create a new session.

### Bug 12: Session EditorState cleared by Compose snapshot revert

**Problem:** `startSession` was called inside a `LaunchedEffect` (coroutine). The `EditorState.updateFromText()` modified a `SnapshotStateList` inside the coroutine's snapshot scope. Compose applied the changes for one render, then reverted them on the next.

**Solution:** Move session creation to a `remember` block (synchronous during composition). Mutations happen in the composition's own snapshot scope and are properly committed.

**Rule:** Never create or modify `SnapshotStateList` / Compose state objects inside `LaunchedEffect`. Use `remember` for synchronous state initialization.

### Bug 13: `computeDirectiveResults` timing

**Problem:** `computeDirectiveResults` ran during composition, but the NoteStore flush happened in a `LaunchedEffect` (after composition). NoteStore had stale content when directives computed.

**Solution:** Push content to NoteStore before directives compute. The `SideEffect` refs + `remember(displayedNoteId)` push happens during composition, before the `computeDirectiveResults` `remember` block.

### Bug 14: Stale directive cache across tab switches

**Problem:** The L1 directive cache held view directive results from a previous visit. When switching to a note with a view, the cache returned stale content.

**Solution:** `MetadataHasher.invalidateCache()` on tab switch (in `loadContent`). The staleness checker detects content hash changes and re-executes.

### Bug 15: Save button z-order in Compose Box

**Problem:** In a Compose `Box`, later children receive touch events first. The save button was the first child and note content was the second — taps went to the content, not the button.

**Solution:** Move the button Row after the note content in the Box.

**Rule:** In Compose `Box`, always put interactive overlays (buttons) as LATER children so they receive touch events.

### Bug 16: Stale generation bump from `executeAndStoreDirectives`

**Problem:** After saving, `executeAndStoreDirectives` ran async and populated the directive cache with the saved content. If the user typed more and switched tabs, the async completion bumped `directiveCacheGeneration`, causing `computeDirectiveResults` to re-run and hit the stale cache.

**Solution:** Guard generation bumps: `if (savedNoteId == currentNoteId) { bumpDirectiveCacheGeneration() }`. Same guard applied in `loadDirectiveResults`.

**Rule:** Async operations that bump directive cache generation must check if the user is still on the same note.

### Bug 17: Inline edit flush overwrites direct edits

**Problem:** The `DataLoadingEffects` `LaunchedEffect` saved the inline session's content on tab switch. If the user had edited the note directly (on another tab) since the session was created, the stale session content would overwrite the direct edit.

**Solution:** Before saving, compare NoteStore's current content with the session's `originalContent`. If NoteStore changed (direct edit happened), skip the inline save — NoteStore already has newer content.

### Bug 18: External change detector resets cursor after save

**Problem:** The `NoteStore.notes.collect` observer detected content changes and called `editorState.updateFromText()`. After save, the observer fired (save updates NoteStore), and since `isSaved` was now true, it applied the update — resetting the cursor.

**Solution:** Skip the observer when `saveStatus` is `Saving` or `Success` — the change is from our own save, not external.

---

## Best Practices

### Async & Coroutines

1. **Always capture `currentNoteId` before `viewModelScope.launch`** — it's a mutable field that can change during suspension
2. **Guard async completion with `noteId == currentNoteId`** — if the user switched tabs, don't apply stale results
3. **Never bump `directiveCacheGeneration` from a stale coroutine** — check noteId first
4. **`loadDirectiveResults` must receive noteId as parameter** — it suspends, so `currentNoteId` can change

### Compose

1. **Never call `NoteStore.updateNote` from a `remember` block** — StateFlow emissions during composition cause unpredictable recomposition
2. **Use `SideEffect` to capture state after composition** — refs hold the "previous" values during the next composition
3. **Create `InlineEditSession` in `remember`, not `LaunchedEffect`** — SnapshotStateList mutations in coroutines can be reverted
4. **`Box` z-order: interactive elements last** — later children in a `Box` receive touch events first
5. **`Handler.removeCallbacksAndMessages(token)` uses reference equality** — don't use String tokens; track Runnables explicitly

### NoteStore

1. **Always update `rawNotes`** in the collection listener — incremental changes are not re-delivered
2. **Only skip rebuild for hot notes** — `rawNotes` stays current, `reconstructedNotes` stays local
3. **Never write to NoteStore or Firestore from the collection listener** — creates feedback loops
4. **Debounce persist captures content at schedule time** — don't re-read from NoteStore at fire time
5. **Cancel prior in-flight persist jobs** — prevents out-of-order Firestore writes

### Inline Editing

1. **InlineEditSession is ephemeral** — reads on creation, writes on save/blur, never intermediate
2. **Always compare `originalContent` vs `editContent`** when deciding to create a new session
3. **Before saving session content on tab switch, verify NoteStore hasn't changed** — skip if the note was edited directly
4. **`getEffectiveDisplayContent` always returns `displayContent`** — never session content (session content can be stale)

### Tab Switching

1. **Push content to NoteStore synchronously during composition** — not in `LaunchedEffect` (too late)
2. **Invalidate `MetadataHasher` on tab switch** — ensures directive cache staleness is detected
3. **Don't flush every keystroke** — only on tab switch (avoids cursor reset from StateFlow emissions)
4. **`LoadStatus.Success` must carry noteId** — guard against stale loads from previous tabs

---

## Cross-Platform Notes

The web app (`useEditor.ts`, `NoteStore.ts`) uses similar patterns:
- `updateNoteSilently` — updates NoteStore without emitting to subscribers (prevents outgoing screen flash)
- `editorStateCache` — only caches dirty editor state (clean notes load from NoteStore)
- `suppressStoreReloadRef` — skips the first NoteStore notification after save
- `noteStore.awaitPendingSave` — prevents loading stale Firestore data during an in-flight save

When modifying one platform, check if the same pattern exists (or should exist) on the other.
