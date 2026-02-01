# View Inline Editing - Caching Issues Record

This document tracks the caching and focus issues encountered during the implementation of view directive inline editing, along with attempted fixes.

## Context

The feature allows users to edit notes displayed within a `[view ...]` directive inline, without navigating away from the host note. The implementation involves:

- `InlineEditSession` - Manages editing state for a viewed note
- `InlineEditState` - Tracks the active session
- `EditableViewNoteSection` - Renders editable note sections within views
- `InlineNoteEditor` - The actual editor component with IME support
- `DirectiveEditRow` - Editor for modifying directive source text

## Issue 1: Mutating Directives Overwriting User Edits

**Symptom**: When a note contained a mutating directive like `[.root.name: "examples"]`, user edits to the first line would be lost. The content appeared to save but was overwritten on reload.

**Root Cause**: When the directive cache became stale due to user edits, the mutating directive was re-executed during `executeAndStoreDirectives`, which would set the name back to the cached value.

**Fix (Working)**:
1. Added `isMutating` flag to `DirectiveAnalysis` in `DependencyAnalyzer.kt`
2. Modified `CachedDirectiveExecutor` to never re-execute mutating directives when cache is stale
3. Added `checkUnwrappedMutations()` to `IdempotencyAnalyzer` that returns an error if property assignments are not wrapped in `once[]`
4. Added check in `DirectiveFinder` to validate unwrapped mutations before execution

**Files Modified**:
- `DependencyAnalyzer.kt` - Added `isMutating` flag
- `CachedDirectiveExecutor.kt` - Skip re-execution of mutating directives
- `IdempotencyAnalyzer.kt` - Added `checkUnwrappedMutations()` method
- `DirectiveFinder.kt` - Added validation check

---

## Issue 2: Second Tap Exits Edit Mode Instead of Moving Cursor

**Symptom**: When tapping on a view to enter edit mode, a second tap would exit edit mode instead of moving the cursor within the text.

**Root Cause**: The `ViewNoteSection` component had a `clickable` modifier that triggered `onNoteTap` on every tap. This caused the focus loss handler to fire and exit edit mode.

**Fix (Working)**:
1. Added `onContentTap` callback parameter to `InlineEditorLine` and `InlineDirectiveOverlayText`
2. Updated tap handlers in `pointerInput` to call `onContentTap` after setting cursor
3. In `InlineNoteEditor`, the `onContentTap` callback calls `focusRequester.requestFocus()` to maintain focus

**Files Modified**:
- `DirectiveAwareLineInput.kt` - Added `onContentTap` callback, updated tap handlers

---

## Issue 3: Focus Lost When Tapping Rendered Directives

**Symptom**: Tapping on a rendered directive (in dashed box) within the inline editor would exit edit mode instead of opening the directive editor.

**Root Cause**: When `toggleDirectiveExpanded` was called, it triggered state changes that caused recomposition. The `DirectiveEditRow` appeared and its `LaunchedEffect` auto-focused its text input, stealing focus from the `InlineNoteEditor`. The focus loss handler detected this and exited edit mode.

**Attempted Fix 1 (Partial)**:
- Check `session.expandedDirectiveKey != null` in focus handler
- Problem: By the time focus loss was detected, `expandedDirectiveKey` was already set, so this worked for OPENING directives but not for CLOSING them.

**Fix (Working)**:
1. Added `isCollapsingDirective` flag to `InlineEditSession`
2. `collapseDirective()` sets this flag to `true` before setting `expandedDirectiveKey = null`
3. Focus handler checks both `expandedDirectiveKey != null` OR `isCollapsingDirective` before deciding to exit
4. `clearCollapsingFlag()` is called when focus is regained
5. Added `LaunchedEffect(expandedDirectiveKey)` that requests focus back when directive is collapsed

**Files Modified**:
- `InlineEditSession.kt` - Added `isCollapsingDirective` flag and `clearCollapsingFlag()` method
- `DirectiveAwareLineInput.kt` - Updated focus handler to check both flags, added LaunchedEffect for focus restoration

---

## Issue 4: Focus Lost When Clicking Confirm Checkmark

**Symptom**: After editing a directive in the inline editor and clicking the confirm checkmark, focus was lost and edit mode exited.

**Root Cause**: Same as Issue 3 - `confirmDirective()` calls `collapseDirective()` which sets `expandedDirectiveKey = null`. The focus loss happened during recomposition when `DirectiveEditRow` was removed.

**Fix (Working)**: The `isCollapsingDirective` flag from Issue 3 also fixed this issue. The sequence is:
1. `confirmDirective()` sets `isCollapsingDirective = true`
2. Then sets `expandedDirectiveKey = null`
3. Focus loss handler sees `isCollapsingDirective = true`, doesn't exit
4. `LaunchedEffect` requests focus back
5. Focus gained, `clearCollapsingFlag()` called

**Files Modified**: Same as Issue 3

---

## Issue 5: View Shows Old Content After Directive Edit (RESOLVED)

**Note**: This issue was resolved through the comprehensive caching audit in `caching-audit-findings.md`. See Phases 1-5 for the complete fix.

---

## Issue 6: Stale Content Flash After Plain Text Inline Edit (RESOLVED)

**Symptom**: When editing plain text (not directives) within a viewed note and tapping out to save, the UI briefly showed OLD content for ~1.5 seconds before updating to show the NEW content.

**Root Cause**: When focus is lost, `editingNoteIndex` is set to `null` synchronously, triggering an immediate UI recompose. The display mode reads `displayContent` from `viewVal.renderedContents`, which comes from `directiveResults`. But `directiveResults` is only updated when `forceRefreshAllDirectives` completes asynchronously ~1.5 seconds later.

**Timeline from logs**:
1. Focus lost → `editingNoteIndex = null` (synchronous)
2. UI recomposes in display mode with STALE content from `directiveResults`
3. Save completes on Firestore (~700ms)
4. `forceRefreshAllDirectives` completes (~800ms more)
5. `directiveResults` updated, UI finally shows fresh content

**Fix**: Use the session's `currentContent` during the transitional state.

The `InlineEditSession` is NOT ended until `forceRefreshAllDirectives` completes. So when `isEditing=false` but the session is still active, we use the session's fresh content instead of the stale `displayContent` from `directiveResults`.

**Implementation** (`DirectiveAwareLineInput.kt`):

1. Added `getEffectiveDisplayContent()` helper function:
```kotlin
internal fun getEffectiveDisplayContent(
    isEditing: Boolean,
    hasActiveSession: Boolean,
    displayContent: String,
    sessionContent: String?
): String {
    return if (!isEditing && hasActiveSession && sessionContent != null) {
        sessionContent  // Use fresh content from session during transitional state
    } else {
        displayContent
    }
}
```

2. Updated `EditableViewNoteSection` to use this helper.

**Limitation**: During the transitional state, directives in the content (like `[add(1,1)]`) will appear as raw text instead of rendered results (like `2`). This is a minor visual artifact that resolves when the refresh completes.

**Test**: `EffectiveDisplayContentTest.kt` - Unit tests for the helper function, including a test that fails without the fix.

**Files Modified**:
- `DirectiveAwareLineInput.kt` - Added helper function, updated display logic
- `EffectiveDisplayContentTest.kt` - New unit test file

---

## Issue 5 (Original): View Shows Old Content After Directive Edit (RESOLVED)

**Symptom**: After editing a directive within a viewed note and confirming:
1. The inline editor recalculates and shows updated result
2. Going to the source note shows the OLD directive and OLD rendered value
3. Going back to the view shows the OLD rendered value
4. Tapping on the note recalculates with the NEW directive
5. Tapping outside shows the OLD rendered value again

**Root Cause**: Multiple caching layers are out of sync:
- The viewed note's content is saved to Firestore
- But the host note's view directive cache still contains old `ViewVal` with old `renderedContents`
- Tab cache may have stale content
- Notes cache may be stale

### Attempted Fix 1: Save + Refresh in onDirectiveEditConfirm

**Change**: In `onDirectiveEditConfirm`, call:
1. `saveInlineNoteContent()` - Save the viewed note
2. `executeDirectivesForContent()` - Update inline editor's directive results
3. `forceRefreshAllDirectives()` - Refresh host note's view directive

**Result**: FAILED - View showed "empty view"

**Why it failed**: `forceRefreshAllDirectives` clears `directiveCacheManager.clearAll()` which clears ALL caches including ones being used by the inline editor. The timing also caused race conditions.

### Attempted Fix 2: Remove forceRefreshAllDirectives

**Change**: Only call `saveInlineNoteContent()` and `executeDirectivesForContent()` in `onDirectiveEditConfirm`, don't call `forceRefreshAllDirectives()`.

**Result**: FAILED - View doesn't refresh at all after confirming directive edit

**Why it failed**: The host note's view directive was never re-executed, so it kept showing cached `ViewVal` with old content.

### Attempted Fix 3: Call forceRefreshAllDirectives in callback

**Change**: Call `forceRefreshAllDirectives()` inside the callback of `executeDirectivesForContent()`, after the inline editor's results are updated:

```kotlin
currentNoteViewModel.executeDirectivesForContent(newContent) { results ->
    session.updateDirectiveResults(results)
    // Now refresh host note's directives
    currentNoteViewModel.forceRefreshAllDirectives(userContent)
}
```

**Result**: STILL FAILING - Caching issues persist

**Current Status**: Logs added with tag `InlineEditCache` to diagnose the issue.

---

## Key Files Involved

| File | Role |
|------|------|
| `InlineEditSession.kt` | Manages inline editing state, tracks expanded directives |
| `DirectiveAwareLineInput.kt` | Contains `EditableViewNoteSection`, `InlineNoteEditor`, focus handling |
| `CurrentNoteScreen.kt` | Wires up callbacks, sets `onDirectiveEditConfirm` |
| `CurrentNoteViewModel.kt` | `saveInlineNoteContent`, `executeDirectivesForContent`, `forceRefreshAllDirectives` |
| `DirectiveCache.kt` | `DirectiveCacheManager` for L1/L2 caching |
| `CachedDirectiveExecutor.kt` | Executes directives with caching |

---

## Cache Invalidation Flow

When a viewed note is edited inline:

1. **saveInlineNoteContent** (synchronous before async save):
   - `cachedNotes = null` (invalidate notes cache)
   - `directiveCacheManager.clearAll()` (invalidate all directive caches)

2. **saveInlineNoteContent** (after save succeeds):
   - Calls `onSuccess` callback

3. **executeDirectivesForContent**:
   - Calls `ensureNotesLoaded()` which fetches fresh notes if `cachedNotes` is null
   - Executes directives for the viewed note's content
   - Returns results to callback

4. **forceRefreshAllDirectives**:
   - Calls `refreshNotesCache()` to fetch fresh notes from Firestore
   - Clears `directiveCacheManager.clearAll()` again
   - Re-parses and re-executes all directives in host note
   - Updates `_directiveResults` LiveData

**Potential Issues**:
- Double clearing of caches (once in save, once in refresh)
- Race conditions between async operations
- `userContent` captured in LaunchedEffect may be stale
- View directive's cached result may be updated but UI doesn't recompose

---

## Debug Log Tags

- `InlineEditCache` - Main tag for tracing the caching flow
- `ViewDirectiveEdit` - Tag in DirectiveAwareLineInput.kt for view-specific logs

Filter with: `adb logcat -s InlineEditCache`
