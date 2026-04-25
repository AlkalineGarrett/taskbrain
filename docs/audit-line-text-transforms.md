# Audit: Line ↔ Text Transformation Code Paths

**Date**: 2026-04-22 (original) · 2026-04-24 (revised)
**Scope**: TaskBrain Android (Kotlin, `app/src/`) and Web (TypeScript, `web/src/`)
**Focus**: All conversions between structured `NoteLine[]` / `LineState[]` and plain text strings

This audit identifies every code path that transforms note lines ↔ text, categorizes them by direction and risk level, and flags sites that may lose noteIds or data during round-trip conversions.

**Status update (2026-04-24)**: Immediate-action recommendations #1, #3, #4, #5 are resolved; #2 has telemetry in place but the structural fix still needs a structured agent output API. See the "Recommendations" section for per-item status.

---

## Executive Summary

### Risk Profile
- **HIGH** (production save paths, loses noteIds): 6 instances
  - `EditorState.updateFromText()` (deprecated on Android, still used on Web test/agent paths)
  - `CurrentNoteViewModel.updateTrackedLines()` (lossy text→lines via reconciliation)
  - `NoteRepository.matchLinesToIds()` on Web (lossy text→lines reconstruction)
  - `InlineSessionManager.syncExternalChanges()` on Web (fallback to updateFromText)
  - `PasteHandler.applySingleLinePaste()` on Web (round-trip through text)
  - `NoteRepository.saveNoteWithFullContent()` on Web (lossy text split)

- **MEDIUM** (visible to user, lossy but less critical): 8 instances
  - Paste handling with `rebuildWithNoteIds()` (content-match lossy)
  - Inline session text updates (may lose noteIds on external changes)
  - Agent content rewriting paths (updateTrackedLines with full reconciliation)

- **LOW** (read-only, display, or test): 10+ instances
  - Text property getters (lines → text via `.join("\n")`)
  - ClipboardParser split/join operations
  - NoteReconstruction joins (read-only reconstruction)
  - Test harnesses and utility functions

### Key Findings
1. **Web `updateFromText()` is deprecated but still in use** (InlineSessionManager fallback at line 77)
2. **Android has equivalent deprecated `updateFromText()` but better guarded** (marked `@Deprecated`, mostly replaced)
3. **Both platforms use lossy similarity matching** that silently drops lines with no content match
4. **Paste operations are partially surgical** (replaceRangeSurgical exists on Web, but single-line paste still round-trips)
5. **Agent rewrite path on Android** uses lossy updateTrackedLines after content changes

---

## Android (Kotlin) Audit

### A. Lines → Text Conversions

#### 1. **EditorState.text property** (line 57)
```kotlin
val text: String get() = lines.joinToString("\n") { it.text }
```
- **Direction**: lines → text
- **Caller**: Selection getters, text comparison, display
- **Purpose**: Retrieve full editor text for selection, comparison, undo state
- **Round-trip risk**: HIGH if result fed back via `updateFromText()`
- **Risk level**: LOW (read-only getter, but dangerous if downstream calls updateFromText)

#### 2. **CurrentNoteViewModel.saveContent()** (line 521)
```kotlin
val content = trackedLines.joinToString("\n") { it.content }
```
- **Direction**: lines → text
- **Caller**: Save path before persisting to repository
- **Purpose**: Convert structured TrackedLines to plain text for NoteStore/Firestore
- **Round-trip risk**: LOW (save is terminal, no round-trip)
- **Risk level**: LOW (save output, not re-parsed)

#### 3. **NoteRepository.reconcileNullNoteIdsByContent()** (line 521 in saveNoteWithChildren)
```kotlin
val linesToSave = reconcileNullNoteIdsByContent(noteId, linesToSaveUnreconciled)
// Later: NoteStore.updateNote(..., existing.copy(content = content), ...)
```
- **Direction**: lines → text
- **Caller**: Save layer before committing to Firestore
- **Purpose**: Preserve content in NoteStore cache during save
- **Round-trip risk**: MEDIUM (cache may be reloaded without noteIds during next load)
- **Risk level**: MEDIUM (cache is used by live queries)

---

### B. Text → Lines Conversions

#### 1. **EditorState.insertTextAt()** (line 267)
```kotlin
val replacementLines = replacement.split("\n")
```
- **Direction**: text → lines
- **Caller**: Paste/insert operations (surgical, not lossy)
- **Purpose**: Split pasted text into lines for insertion
- **Preserves noteIds**: YES (new lines get SURGICAL sentinels)
- **Risk level**: LOW (surgical insert, noteIds preserved)

#### 2. **EditorState.replaceFirstLineContent()** (line 614)
```kotlin
val newLines = newContent.split("\n")
```
- **Direction**: text → lines
- **Caller**: Directive-driven content rewrites
- **Purpose**: Split multi-line directive output into lines
- **Preserves noteIds**: PARTIAL (first line preserves noteIds, new lines get DIRECTIVE sentinels)
- **Risk level**: LOW (surgical, metadata preserved)

#### 3. **EditorState.appendContent()** (line 652)
```kotlin
val newLines = content.split("\n")
```
- **Direction**: text → lines
- **Caller**: Directive append operations
- **Purpose**: Split appended content into lines
- **Preserves noteIds**: NO (all lines get DIRECTIVE sentinels)
- **Risk level**: LOW (new content, not lossy)

#### 4. **EditorState.updateFromText()** (line 691) ⚠️ **DEPRECATED**
```kotlin
@Deprecated("Lossy: reconciles noteIds by content match...")
internal fun updateFromText(newText: String) {
    val oldNoteIds = lines.map { it.noteIds }
    val oldContents = lines.map { it.text }
    val newLines = newText.split("\n")
    
    val reconciled = org.alkaline.taskbrain.data.reconcileLineNoteIds(
        oldContents = oldContents,
        oldNoteIds = oldNoteIds,
        newContents = newLines,
        onUnmatchedNonEmpty = { idx, content -> unmatched.add(idx to content) },
    )
```
- **Direction**: text → lines
- **Caller**: ~~Cut/paste/replace (replaced with surgical operators)~~ Now mostly replaced
- **Purpose**: Lossy fallback for rebuilding lines after external text changes
- **Preserves noteIds**: NO (silently drops noteIds on unmatched lines, via similarity matching)
- **Round-trip risk**: **CRITICAL** (this is the root cause of noteId loss bugs)
- **Risk level**: **HIGH** (marked deprecated, but still exists; may be called by accident)
- **Deprecation notes**: `reconcileLineNoteIds` uses LCS-based similarity matching which drops unmatched lines

---

### C. Round-Trip Conversions (Lines → Text → Lines)

#### 1. **CurrentNoteViewModel.updateTrackedLines()** (lines 451–499)
```kotlin
fun updateTrackedLines(newContent: String) {
    val newLinesContent = newContent.lines()  // text → list of strings
    
    // Falls back to null noteIds if oldLines is empty
    if (oldLines.isEmpty()) {
        currentNoteLines = newLinesContent.mapIndexed { index, content ->
            NoteLine(content, if (index == 0) currentNoteId else null)
        }
        return
    }
    
    val reconciled = reconcileLineNoteIds(
        oldContents = oldContents,
        oldNoteIds = oldNoteIds,
        newContents = newLinesContent,
        onUnmatchedNonEmpty = { idx, content -> unmatched.add(idx to content) },
    )
```
- **Direction**: text → lines (via lossy matching)
- **Caller**: Agent content rewriting (`processAgentCommand` at line 556)
- **Purpose**: Reparse agent output into tracked lines
- **Preserves noteIds**: **NO** (reconciliation drops unmatched lines)
- **Round-trip risk**: **HIGH** (called after agent modifies content, lossy matching)
- **Risk level**: **HIGH** (agent path is production, lossy reconciliation)
- **Mitigation**: Line logging on unmatched (line 489-492), but silent drops remain

---

### D. Surgical/Safe Conversions (No Round-Trip)

#### 1. **PasteHandler.splitLine()** (implicit in paste flow)
- Uses `splitNoteIds()` from ContentSimilarity for noteId distribution
- **Preserves noteIds**: YES (stamps SPLIT sentinels)
- **Risk level**: LOW (surgical)

#### 2. **LineReconciliation.reconcileLineNoteIds()** (pure function)
- **Direction**: text → lines
- **Purpose**: Match new line texts to old lines via content similarity
- **Preserves noteIds**: PARTIAL (by similarity match only; exact non-matches lose ids)
- **Risk level**: MEDIUM (used as fallback, lossy by design)

#### 3. **NoteStore.getNoteLinesByIdOrSynthesize()** (line 292)
```kotlin
return fallbackContent.split("\n").mapIndexed { index, line ->
    NoteLine(line, if (index == 0) noteId else null)
}
```
- **Direction**: text → lines
- **Caller**: Fallback init when note not in NoteStore
- **Purpose**: Synthesize lines from plain content when structured data unavailable
- **Preserves noteIds**: NO (only first line gets noteId, rest are null)
- **Risk level**: MEDIUM (fallback init path, causes null noteId on subsequent save)

---

### E. Deprecated/Legacy APIs (Android)

| API | Location | Status | Notes |
|-----|----------|--------|-------|
| `EditorState.updateFromText()` | line 681 | `@Deprecated` | Marked warning, avoid all calls |
| `reconcileLineNoteIds()` | `LineReconciliation.kt` | OK (utility) | Used as fallback, lossy by design |
| `matchLinesByContent()` | `LineReconciliation.kt` | OK (utility) | Phase 1 exact + phase 2 similarity |

---

## Web (TypeScript) Audit

### A. Lines → Text Conversions

#### 1. **EditorState.text property** (line 43)
```typescript
get text(): string {
  return this.lines.map((l) => l.text).join('\n')
}
```
- **Direction**: lines → text
- **Caller**: Selection getters, text comparison, clipboard
- **Purpose**: Retrieve full editor text
- **Round-trip risk**: HIGH if result fed to `updateFromText()`
- **Risk level**: LOW (read-only getter, but dangerous downstream)

#### 2. **InlineEditSession.getText()**
```typescript
getText(): string {
  return this.editorState.lines.map((l) => l.text).join('\n')
}
```
Empty lines round-trip as their own Firestore docs post-migration, so no trailing-empty stripping is needed.
- **Direction**: lines → text
- **Caller**: Dirty tracking, save operations
- **Purpose**: Get current content for comparison/save
- **Round-trip risk**: MEDIUM (result may be re-parsed if external change arrives)
- **Risk level**: MEDIUM (dirty comparison may trigger updateFromText fallback)

#### 3. **NoteReconstruction.reconstructNoteContent()** (line 134)
```typescript
const joined = lines.map(l => l.content).join('\n')
const result = joined === note.content ? note : { ...note, content: joined }
```
- **Direction**: lines → text
- **Caller**: Note reconstruction from tree (read-only)
- **Purpose**: Join reconstructed lines back into note.content for display
- **Round-trip risk**: LOW (output is read-only)
- **Risk level**: LOW (reconstruction output, not re-parsed)

---

### B. Text → Lines Conversions

#### 1. **EditorState.replaceRangeSurgical()** (line 150)
```typescript
const parts = replacement.split('\n')
```
- **Direction**: text → lines
- **Caller**: Replace/paste operations (surgical)
- **Purpose**: Split replacement text at newlines
- **Preserves noteIds**: YES (new lines get SURGICAL sentinels, edge lines keep noteIds)
- **Risk level**: LOW (surgical, noteIds preserved)

#### 2. **EditorState.updateFromText()** (line 424) ⚠️ **DEPRECATED but still in use**
```typescript
@deprecated Lossy — reconciles noteIds by content match...
updateFromText(newText: string): void {
  const oldLines = this.lines
  const newLineTexts = newText.split('\n')
  
  // Exact match phase
  // Similarity match phase via performSimilarityMatching()
  
  this.lines = newLineTexts.map((t, i) => 
    new LineState(t, undefined, matchedNoteIds[i] ?? [])
  )
```
- **Direction**: text → lines
- **Caller**: Agent result rewrites, fallback on external changes
- **Purpose**: Lossy fallback for parsing new text back into lines
- **Preserves noteIds**: NO (silently drops noteIds on unmatched lines)
- **Round-trip risk**: **CRITICAL**
- **Risk level**: **HIGH** (still actively used, not guarded)

#### 3. **ClipboardParser.parseInternalLines()** (Web `src/editor/ClipboardParser.ts`)
```typescript
export function parseInternalLines(text: string): ParsedLine[] {
  return text.split('\n').map(parseInternalLine)
}
```
- **Direction**: text → lines (parse structure, not noteIds)
- **Caller**: Paste handler
- **Purpose**: Parse clipboard content into structured lines
- **Preserves noteIds**: N/A (creates new ParsedLine, not LineState)
- **Risk level**: LOW (parse only, noteIds assigned later)

#### 4. **NoteRepository.saveNoteWithFullContent()** (line 409)
```typescript
const newLinesContent = newContent.split('\n')
const trackedLines = matchLinesToIds(noteId, existingLinesNoTrailing, newLinesContent, editorNoteIds)
```
- **Direction**: text → lines via lossy matching
- **Caller**: Inline edit save path
- **Purpose**: Reparse edited content back into lines with noteIds
- **Preserves noteIds**: PARTIAL (via matchLinesToIds which uses content matching)
- **Round-trip risk**: **MEDIUM** (lossy similarity matching)
- **Risk level**: **MEDIUM** (save path, but lossy)

---

### C. Round-Trip Conversions (Lines → Text → Lines)

#### 1. **PasteHandler.applySingleLinePaste()** (lines 107–116)
```typescript
// Single-line text insertion with selection
const fullText = lines.map(l => l.text).join('\n')  // lines → text
const newText = fullText.substring(0, sMin) + text + fullText.substring(sMax)
const rebuilt = rebuildWithNoteIds(newText.split('\n'), lines)  // text → lines
```
- **Direction**: lines → text → lines (round-trip)
- **Caller**: Single-line paste with selection
- **Purpose**: Apply text edit via round-trip
- **Preserves noteIds**: **NO** (rebuildWithNoteIds uses content matching, drops unmatched)
- **Round-trip risk**: **CRITICAL** (exact round-trip, loses noteIds on any text change)
- **Risk level**: **HIGH** (paste path is user-facing, lossy)
- **Mitigation**: Should use replaceRangeSurgical instead

#### 2. **InlineSessionManager.syncExternalChanges()** (lines 64–81)
```typescript
const storeLines = noteStore.getNoteLinesById(noteId)
if (storeLines) {
  const noteLines = storeLines.map(nl => ({
    text: nl.content,
    noteIds: nl.noteId ? [nl.noteId] : [],
  }))
  session.editorState.initFromNoteLines(noteLines, true)
} else {
  session.editorState.updateFromText(newContent)  // FALLBACK to lossy path
}
```
- **Direction**: text → lines (fallback via lossy updateFromText)
- **Caller**: Sync external note changes into inline session
- **Purpose**: Update session when note changes from Firestore
- **Preserves noteIds**: FALLBACK NO (updateFromText is lossy)
- **Round-trip risk**: **MEDIUM** (fallback only, but still lossy)
- **Risk level**: **HIGH** (fallback to updateFromText, live note changes)

#### 3. **PasteHandler.rebuildWithNoteIds()** (lines 313–352)
```typescript
function rebuildWithNoteIds(newTexts: string[], oldLines: LineState[]): LineState[] {
  // Phase 1: exact content match
  // Phase 2: similarity-based matching
  
  return newTexts.map((t, i) => {
    const ids = matchedNoteIds[i] ?? [newSentinelNoteId('paste')]
    return new LineState(t, undefined, ids)
  })
}
```
- **Direction**: text → lines (lossy rebuild)
- **Caller**: Paste flow (applySingleLinePaste, applyFullLineReplace)
- **Purpose**: Match pasted text back to old lines via content
- **Preserves noteIds**: **NO** (content match only, stamps PASTE sentinels on unmatched)
- **Risk level**: **MEDIUM** (lossy, but expected for paste with new content)

#### 4. **NoteRepository.matchLinesToIds()** (lines 676–727)
```typescript
export function matchLinesToIds(
  parentNoteId: string,
  existingLines: NoteLine[],
  newLinesContent: string[],
  editorNoteIds?: (string | null)[],
): NoteLine[] {
  // Phase 0: use editor-provided noteIds for foreign notes
  // Phase 1: exact content match
  // Phase 2: similarity-based matching
  // Fallback: positional match for any remaining unmatched lines
  
  return newLinesContent.map((content, index) => ({
    content,
    noteId: newIds[index] ?? null,
  }))
}
```
- **Direction**: text → lines (multi-phase lossy matching)
- **Caller**: saveNoteWithFullContent, inline save fallback
- **Purpose**: Match inline-edited content back to existing lines
- **Preserves noteIds**: **NO** (falls back to positional match, then null)
- **Round-trip risk**: **HIGH** (lossy with positional fallback)
- **Risk level**: **HIGH** (save path for inline edits)

---

### D. Surgical/Safe Conversions

#### 1. **EditorState.initFromNoteLines()** (line 477)
```typescript
initFromNoteLines(noteLines: Array<{ text: string; noteIds: string[] }>, preserveCursor = false): void {
  this.lines = noteLines.map((nl) => new LineState(nl.text, undefined, nl.noteIds))
```
- **Direction**: lines → lines (structured reload)
- **Caller**: Load from NoteStore, external change reloads
- **Purpose**: Re-seed editor from structured line data
- **Preserves noteIds**: YES (direct copy)
- **Risk level**: LOW (surgical, no text matching)

#### 2. **EditorController.insertText()** (line 183 onwards)
```typescript
insertText(lineIndex: number, text: string): void {
  if (text.includes('\n')) {
    const parts = text.split('\n')
    parts.forEach((part, index) => {
      if (index > 0) {
        this.splitLine(lineIndex + index - 1)
      }
      // insert part...
    })
```
- **Direction**: text → lines (surgical split)
- **Caller**: IME input, typing
- **Purpose**: Split multi-line input at newlines
- **Preserves noteIds**: PARTIAL (splitLine preserves on original, new gets sentinels)
- **Risk level**: LOW (surgical)

---

### E. Deprecated/Legacy APIs (Web)

| API | Location | Status | Notes |
|-----|----------|--------|-------|
| `EditorState.updateFromText()` | line 422 | Marked `@deprecated` | Still actively used (InlineSessionManager line 77) |
| `PasteHandler.rebuildWithNoteIds()` | line 313 | OK (utility) | Lossy content matching, expected for new paste content |
| `NoteRepository.matchLinesToIds()` | line 676 | CRITICAL | Multi-phase lossy, positional fallback (should never need fallback) |

---

## Summary Table: All Transformation Sites

### HIGH-RISK Sites (Production, Lossy, May Lose Data)

| # | File | Line | Direction | Lossy? | Notes |
|---|------|------|-----------|--------|-------|
| 1 | `EditorState.kt` | 691 | text→lines | **YES** | `@Deprecated updateFromText()` – reconciliation drops unmatched |
| 2 | `CurrentNoteViewModel.kt` | 451 | text→lines | **YES** | Agent rewrite path – lossy updateTrackedLines |
| 3 | `NoteRepository.ts` | 409 | text→lines | **YES** | `saveNoteWithFullContent()` – matchLinesToIds lossy |
| 4 | `EditorState.ts` | 424 | text→lines | **YES** | `@deprecated updateFromText()` – still used (InlineSessionManager line 77) |
| 5 | `PasteHandler.ts` | 107 | lines→text→lines | **YES** | `applySingleLinePaste()` – round-trip with selection, lossy rebuild |
| 6 | `InlineSessionManager.ts` | 77 | text→lines | **YES** | Fallback to `updateFromText()` when NoteStore empty |

### MEDIUM-RISK Sites (Visible to User, Lossy but Expected)

| # | File | Line | Direction | Notes |
|---|------|------|-----------|-------|
| 1 | `PasteHandler.ts` | 313 | text→lines | `rebuildWithNoteIds()` – lossy, but stamps PASTE sentinels on new content |
| 2 | `NoteRepository.ts` | 597 | lines→text | `reconcileNullNoteIdsByContent()` – recovery path, lossy fallback |
| 3 | `PasteHandler.ts` | 130 | lines→text→lines | Full-line replace – round-trip via rebuildWithNoteIds |
| 4 | `InlineSessionManager.ts` | 64 | text→lines | Fallback sync path – lossy if NoteStore stale |

### LOW-RISK Sites (Read-Only, Display, or Test)

| # | File | Line | Purpose | Risk |
|---|------|------|---------|------|
| 1 | `EditorState.kt` | 57 | Text property getter | Read-only, dangerous if downstream calls updateFromText |
| 2 | `EditorState.ts` | 43 | Text property getter | Read-only, dangerous if downstream calls updateFromText |
| 3 | `NoteReconstruction.ts` | 134 | Join reconstructed lines | Read-only output |
| 4 | `InlineEditSession.ts` | 10 | Get current text | Dirty tracking comparison |
| 5 | `ClipboardParser.ts` | split/join | Parse clipboard | Structure parsing, noteIds assigned later |
| 6 | `NoteStore.kt` | 292 | Synthesize fallback lines | Fallback when note not in store (causes null noteIds) |

---

## Recommendations

### Immediate Actions (Blocking Data Loss)

1. **✅ RESOLVED (2026-04-24) — Web: InlineSessionManager.syncExternalChanges()**
   - **Fix landed**: When `noteStore.getNoteLinesById(noteId)` returns undefined, the sync now skips (logs a warning) rather than falling back to `updateFromText`. A subsequent snapshot that restores structured lines triggers another sync.
   - **File**: `web/src/editor/InlineSessionManager.ts`

2. **⚠️ PARTIAL (2026-04-24) — Android: updateTrackedLines() agent path**
   - **Landed**: Full diagnostic block (mirrors `buildNullIdRecoveryDiagnostics`) now emitted on any unmatched non-empty line, including stack trace, before/after line dumps, and reason. See `CurrentNoteViewModel.buildUpdateTrackedLinesDiagnostics`.
   - **Remaining**: The structural fix requires a structured agent output API — the agent currently returns plain text with no line-identity hints, so any content-based reconciliation is inherently lossy on substantial rewrites. Tracked as a separate design item.

3. **✅ RESOLVED (2026-04-24) — Web: applySingleLinePaste() round-trip**
   - **Fix landed**: The selection branch no longer round-trips through `fullText` + `rebuildWithNoteIds`. It now performs a surgical merge (prefix + text + suffix on startLine, splice out lines in between) that preserves `startLine.noteIds`. Mirrors `EditorState.replaceRangeSurgical`'s edge-id handling.
   - **File**: `web/src/editor/PasteHandler.ts`
   - **Test**: `single-line paste with selection preserves startLine noteIds` in `PasteHandler.test.ts`.

### Secondary Actions (Migration Candidates)

4. **✅ RESOLVED (2026-04-24) — Web: matchLinesToIds() positional fallback**
   - **Fix landed**: Phases 3 (positional fallback) now records every binding it makes AND every non-empty line that remained null. When either set is non-empty, a full diagnostic block is logged (matches the null-id-recovery style: parent id, counts, per-line detail, stack trace). Keeps the fallback for correctness but makes invocations visible for monitoring.
   - **File**: `web/src/data/NoteRepository.ts` — `buildMatchLinesToIdsDiagnostics`.

5. **✅ RESOLVED (2026-04-24) — Android: NoteStore.getNoteLinesByIdOrSynthesize()**
   - **Fix landed**: Two-pronged.
     - The synthesize fallback itself now logs a warning identifying how many non-root lines received null noteIds and reminding callers that only NEW-session paths should use it.
     - The sync-on-external-change call site in `DirectiveAwareLineInput.kt:967` no longer uses the synthesize fallback; it calls `getNoteLinesById` and skips the sync when structured lines aren't available (parallel with the web `syncExternalChanges` fix).
   - **Files**: `app/src/main/java/.../data/NoteStore.kt`, `.../ime/DirectiveAwareLineInput.kt`.

6. **⏳ DEFERRED — Both: Replace reconcileLineNoteIds() with splitLine/splitNoteIds for directive rewrites**
   - **Status**: Not yet addressed. Requires directive-specific APIs that split content surgically. Tracked for a later design pass.

### Code Quality (Non-Blocking)

7. **⏳ DEFERRED — Both: Document round-trip hazards in EditorState**
   - Still a good-to-have.

8. **⚠️ PARTIAL — Both: Telemetry on reconciliation drops**
   - Covered for `updateTrackedLines` (Android, rec #2) and `matchLinesToIds` (web, rec #4). Other reconciliation sites still have only one-line warnings.

9. **⏳ DEFERRED — Both: Test suite for round-trip safety**
   - Partial: added paste-selection preservation test (web) and paste-noteId-preservation tests on both platforms as part of earlier fixes. Agent-rewrite and inline-sync round-trip tests still to do.

---

## References

- **Architecture docs**: `/Users/alkaline9/workspace/taskbrain/docs/save-robustness-architecture.md`
- **Directive caching**: `/Users/alkaline9/workspace/taskbrain/docs/directive-caching-details.md`
- **Test coverage**: 
  - Android: `/Users/alkaline9/workspace/taskbrain/app/src/test/java/org/alkaline/taskbrain/ui/currentnote/NoteIdPropagationTest.kt`
  - Web: `/Users/alkaline9/workspace/taskbrain/web/src/__tests__/editor/NoteIdPropagation.test.ts`

---

**End of Audit**
