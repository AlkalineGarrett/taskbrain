# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This file is loaded on every turn — keep it lean. Subsystem-specific guidance lives in nested `CLAUDE.md` files (auto-loaded when working in that directory) and `docs/*.md` (imported by the relevant subsystem CLAUDE.md). Don't duplicate guidance here; link to it.

## Project Overview

TaskBrain is a cross-platform ADHD task management app with a Firebase backend. It has two clients:
- **Android app** (Kotlin, Jetpack Compose) — the primary app in `app/`
- **Web app** (TypeScript, React) — in `web/`

## Build commands

Build/test/run commands are platform-specific and live in the appropriate subtree (app/ or web/).

## Local test environment

Use the wrapper scripts; **don't invoke `firebase emulators:start` or `emulator -avd …` directly.**

```bash
scripts/test-env-up.sh             # Firebase emulator only (web tests + manual)
scripts/test-env-up.sh --with-avd  # Adds the Android AVD for instrumentation tests
scripts/test-env-down.sh           # Tears it all down
```

Both scripts are idempotent — re-running is a no-op when things are already up. They handle PID files, log paths, AVD readiness, and emulator data persistence so you don't have to remember the exact incantation each time. Platform-specific gradle / vite / vitest invocations against this environment live in the per-platform `CLAUDE.md`.

**Do not run `firebase deploy --only firestore:rules`.** The repo's
`firebase.json` points the rules path at the *permissive* emulator file —
deploying it would open prod to any signed-in user. Production rules live
in `firestore.rules` and are deployed manually via the Firebase console.

## Architecture

**Pattern:** MVVM with Jetpack Compose UI on Android; React + hooks on web.

**Data flow:** Compose Screens / React components → ViewModels / hooks → NoteRepository → Firebase Firestore.

**Key directories:**
- `app/src/main/java/org/alkaline/taskbrain/data/` — Android data layer (models, repository, NoteStore)
- `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/` — Android editor + screens
- `web/src/data/` — web data layer
- `web/src/editor/` — web editor
- `web/src/hooks/` — React hooks bridging editor + components
- `app/src/test/`, `web/src/__tests__/` — unit tests (JUnit 4 + MockK on Android, Vitest on web)

## Foundational invariants (apply everywhere)

**Every line is a Firestore document:**
- Each editor line — including empty lines — round-trips as its own Firestore doc.
- No auto-appended trailing-empty UI line; no trailing-empty stripping on save.
- New empty lines get a TYPED/SPLIT sentinel noteId at edit time so save can allocate fresh docs for them.

**Line identity invariant (strict structural):**
- `NoteLine.noteId` is non-nullable (`String` on Android, `string` on web). Every line carries either a real Firestore doc id or a sentinel ("new line, allocate fresh"). The type system enforces this — null arrival is impossible without an `as any` / unsafe cast. Identity is structural, never recovered by content match.
- Editor sessions are initialized only from structurally-valid tracked lines. Inline-edit / view-directive sessions resolve real ids via `NoteRepository.loadNoteLinesAwait` (awaits the listener, falls back to a Firestore one-shot read on timeout). Synthesizing lines from a content string is forbidden.
- Sentinels are never content-matched against existing siblings during save — they always allocate fresh docs. Aliasing a typed line to an existing line because their content matches would silently merge two distinct lines into one Firestore doc.

**Note structure in Firestore:**
- First line = parent note content.
- Additional lines = contained child notes (via `containedNotes` array, ordered list of real document IDs only).
- An empty-string entry (`""`) in `containedNotes` is data corruption — `reconstructNoteLines` logs it at error level and drops it.
- Deletion is soft (`state = "deleted"`). Soft-delete preserves `parentNoteId` / `rootNoteId` so the deletion can be inspected and undone. See subsystem docs for the per-line `deletionBatchId` design.

**Whitespace semantics:**
- Empty content string is a real, persisted, addressable line.
- Whitespace-only content (`"   "`) is also content — same as any other line.

## When to read which doc

These triggers live here (not inside the docs) so the agent knows to load them. Don't skip — most of these guard against bugs that have already shipped at least once.

- **Adding or changing a Firestore read/write** → `docs/firestore-efficiency.md` *before* writing the code. Every new read/write must call `firestoreUsage.recordRead` / `recordWrite` for the usage report to catch regressions.
- **Catching an error / writing a `try`/`catch` / handling a `Result.onFailure`** → `docs/error-handling.md`. The repo's stance on silent failures is unusual (alpha-stage; surface even implementation details), so check before defaulting to "log and move on."
- **Landing a non-trivial change on either platform** → `docs/cross-platform-parity.md` *before submission*. The two platforms mirror each other (with one documented exception); a feature or fix on one almost always needs porting to the other in the same PR.
- **Touching the snapshot listener, NoteStore, editor-reload, or directive-cache code** → `docs/live-cross-platform-sync.md`. Three layers with strict responsibilities — getting them wrong leads to either echo loops or stale views.
- **Adding a method to `EditorController` / `EditorState`, or wiring an `onFocusChanged` / `onSelectionChanged` / `onScroll` callback** → `docs/editor-callback-intent-vs-notification.md`. There's a known IME-storm trap; the rule prevents it.
- **Adding or changing a mutation that has undo/redo semantics** → `docs/undo-redo-architecture.md`, plus `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/requirements.md` for the full spec.
- **Starting a refactor** → `docs/refactoring-workflow.md` (process), `docs/refactoring-checklist.md` (what to look for), `docs/refactoring-backlog.md` (tracked items).
- **Working on alarms / a feature listed in a `requirements.md`** → that requirements file, plus the relevant `docs/*.md` (e.g., `alarm-requirements.md`, `paste-requirements.md`).

## Subsystem-specific guidance

These auto-load via nested `CLAUDE.md` when you're working in the subtree:

- **Editor (Android):** `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/CLAUDE.md`
- **Editor (web):** `web/src/editor/CLAUDE.md`
- **Data layer (Android):** `app/src/main/java/org/alkaline/taskbrain/data/CLAUDE.md`
- **Data layer (web):** `web/src/data/CLAUDE.md`
- **React hooks (web):** `web/src/hooks/CLAUDE.md`

