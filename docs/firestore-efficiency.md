# Firestore Efficiency Principles

Distilled from the April 2026 web/Android parity pass that brought web's Firestore read/write volume in line with Android's. Read this before adding new Firestore reads or writes on either platform.

The diagnostic that drives this work is `FirestoreUsage` — every read and write is bucketed by operation name and event type (DOC_GET, GET_DOCS, LISTENER_INITIAL_FRESH/CACHED, LISTENER_UPDATE_FRESH/CACHED, LISTENER_LOCAL_ECHO, SET, UPDATE, DELETE, BATCH_COMMIT, TRANSACTION). The "Billed" section of the report shows what the user actually pays for; the "Local-only" section shows cache hits and echoes. If a new operation isn't instrumented, regressions hide.

## Principles

### 1. One listener per collection, served from cache

Long-lived data (notes, openTabs, recent searches) is owned by a snapshot listener that lazily attaches on first use and populates an in-memory cache. All subsequent reads — including length checks, lookups by ID, and dedup checks — go through the cache. `getDoc` / `getDocs` are reserved for one-shots that genuinely don't repeat.

The listener absorbs Firestore's persistent cache: on cold start, the SDK delivers from IndexedDB (LISTENER_INITIAL_CACHED — local-only, $0). The first server-confirmed snapshot is one INITIAL_FRESH read; everything after that is delta-only via resume tokens. `getDocs` always hits the server when online — no IndexedDB shortcut — so it's the wrong choice for anything called more than once per session.

Reference: `RecentTabsRepository.ts` / `RecentTabsRepository.kt` (`ensureListenerAttached`); `NoteStore.ts` / `NoteStore.kt` (`start` + `ensureLoaded`).

### 2. Wait for the listener, don't race it with a direct read

When a screen needs a specific note before the listener has delivered its first snapshot, the wrong fix is a parallel `getDoc` — that's a billed server fetch for data the listener is about to deliver from cache for free. The right fix is to wait briefly (≤1500ms) for `ensureLoaded()` and then serve from the in-memory store. Only fall through to a direct read if the wait times out or the store doesn't have the note (e.g., a brand-new doc not yet echoed back).

Reference: `NoteRepository.loadNoteWithChildren` on both platforms — `if (!noteStore.isLoaded()) await ensureLoaded()` with timeout, then `getRawNoteById` / `getNoteLinesById`, then fall back to Firestore.

### 3. Dedup against persistent state, not transient state

A "skip if unchanged" guard is only as good as the state it reads. UI state (React component state, Compose `remember` values) is wiped on remount or recomposition. Dedup checks must read from a location that survives navigation — the listener cache, a ViewModel, or a singleton store.

The web's recent-tabs bug came from exactly this trap: the dedup check read React state, which was empty after every Admin → CurrentNote remount, so every re-entry rewrote the same tab. Fix: read from the listener cache via `repo.getOpenTabs()`.

### 4. Match the write op to the actual change

`setDoc` overwrites every field, including server-timestamped ones. Calling `setDoc` for a title-only change re-stamps `lastAccessedAt`, which can reorder lists, fire listener echoes for unchanged data, and cascade into other writes. Use `updateDoc` for partial updates. Use `setDoc` only when you genuinely intend the full-document semantics.

Web example: `updateTabDisplayText` was incorrectly calling `repo.addOrUpdateTab` (a SET); fixed to `repo.updateTabDisplayText` (UPDATE).

### 5. Never read the whole collection just to enforce a limit

With a listener-backed cache holding the full collection, length checks and trim operations are free. Querying Firestore with no `limit()` to count documents — only to delete the excess — is the worst-of-both-worlds: you pay reads for documents you'll immediately delete.

Reference: `enforceTabLimit` reads `cachedTabs` (length check on memory) and only issues a batch delete when over the cap.

### 6. Reserve the local-echo channel for echoes

Snapshot listeners deliver three things: the initial payload, server-confirmed updates, and local echoes of the client's own pending writes. Local echoes (`snapshot.metadata.hasPendingWrites`) shouldn't update derived caches — the cache already reflects the optimistic state. Skip them and instrument them as `LISTENER_LOCAL_ECHO` so they show up in the local-only section of the usage report instead of inflating apparent "billed" reads.

Reference: every listener that records reads checks `snapshot.metadata.hasPendingWrites` first.

### 7. Instrument every read and write

A read that isn't counted is a regression that won't be caught. Every `getDoc`, `getDocs`, `setDoc`, `updateDoc`, `deleteDoc`, batched commit, transaction, and snapshot delivery records to `FirestoreUsage`. The operation name should match the function (`loadNoteWithChildren`, `enforceTabLimit`, `RecentTabsRepo.listener`) so the report points at code, not symptoms. Listener events are tagged by initial-vs-update and fresh-vs-cached so the user can see at a glance whether the IndexedDB persistence layer is doing its job.

If you add a new code path that touches Firestore and skip instrumentation, the next usage audit will see a hole.

## Anti-patterns this catches

- A `getDocs` query without a `limit()` called inside a hot path (per-tab-open, per-keystroke, per-render).
- A `setDoc` write fired on every screen mount with no dedup.
- Dedup logic reading from local UI state that's reset by navigation.
- Direct `getDoc` falls back issued in parallel with a listener that's about to deliver the same data from IndexedDB.
- A length check implemented as a full collection read.
- A new repository method that doesn't call `firestoreUsage.recordRead/recordWrite`.

## When to reach for a non-cached read

Listeners aren't free — they hold network connections and consume INITIAL_FRESH on first attach. Direct `getDoc`/`getDocs` is appropriate when:

- The data is one-shot and never re-read in the session (e.g., a one-time migration check).
- The query is parameterized in a way that doesn't fit a single listener (e.g., search across arbitrary fields).
- The caller already has a listener that would deliver the same data and the cost of a direct read is meaningfully lower than the cost of waiting.

In every other case, prefer the listener.
