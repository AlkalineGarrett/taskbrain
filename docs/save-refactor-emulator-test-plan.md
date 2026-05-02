# Save-refactor emulator test plan (Android)

Plan for instrumentation tests against the Firebase Emulator Suite that
cover the save-path refactor (Phases 0–9 + async-only fix + type tightening).
Complements the existing unit tests (`./gradlew test`) by exercising
behaviors that only manifest with real Firestore listener timing, real
batched commits, real concurrent clients, and real persistence.

## Goals

The unit suite mocks Firestore and the listener flow. It catches planner
logic, state-machine transitions, and assertion failures. It cannot catch:

- **Listener echo timing.** Whether a save's own server echo arrives before
  or after a follow-up edit, and whether the editor reloads spuriously.
- **Real batched commit semantics.** All-or-nothing atomicity when the
  Firestore client rejects part of the batch.
- **Multi-client concurrency.** Two clients mutating the same note's
  `containedNotes` and observing each other through the listener.
- **Cold-cache cross-session behavior.** A deep-link hits `loadNoteLinesAwait`
  before the listener has heard of the note; the await + Firestore-read
  fallback paths fire in production realistically.
- **Persistence + offline.** Edits queued offline survive process restart
  and sync on reconnect.
- **Echo-suppression timing.** Characters typed during a save's `lastWriterOpId`
  echo window aren't clobbered when the snapshot arrives.

## Existing infrastructure

`app/src/androidTest/java/org/alkaline/taskbrain/EmulatorTestSupport.kt`
already provides:

- `requireEmulatorAndSignIn()` — `@BeforeClass`-style guard that skips when
  `-PuseFirebaseEmulator=true` wasn't passed; ensures anonymous auth.
- `seedMultiLineNote(content)` — bypass-the-UI seeder via `NoteRepository`.
- `ComposeContentTestRule.waitForNoteInStore(seedId)` — waits for the
  listener echo to populate `NoteStore`.
- `waitAndClickText`, `waitAndClickContentDescription` — UI-driving
  helpers.

CLAUDE.md documents the run command:

```bash
firebase emulators:start                                              # Terminal A
~/Library/Android/sdk/emulator/emulator -avd … &> /tmp/avd.log &      # AVD
./gradlew connectedAndroidTest -PuseFirebaseEmulator=true             # tests
```

## What this plan adds

A new top-level package
`app/src/androidTest/java/org/alkaline/taskbrain/saverefactor/` containing
data-layer-focused tests (no Compose) that drive `NoteRepository` +
`NoteStore` directly. Faster than UI tests; one launch of FirebaseApp
covers many tests.

Plus a few Compose-driven scenarios that need real keystroke timing
(echo suppression).

A second auth context — created via `FirebaseApp.initializeApp(context, options, "secondary")` — gives us a "second client" without a second device.

---

## Test catalog

Organized by phase. Each test names the **scenario**, **why it needs the
emulator**, **setup**, and **assertions**.

### Phase 0 — line identity foundation

#### IT-0a `loadNoteWithChildren returns real ids end-to-end`
- **Why emulator:** real listener delivers descendant docs; verifies the
  reconstruction walk on real `containedNotes` arrays.
- **Setup:** seed root + 3 descendants via repo; `clear()` NoteStore;
  re-attach listener.
- **Assert:** `loadNoteLinesAwait(rootId)` returns lines whose `noteId`
  fields are the real Firestore doc ids; no sentinels, no nulls.

#### IT-0b `editor session-init survives the listener gap via repo fallback`
- **Why emulator:** simulates the case the async fix targets — note exists
  in Firestore but listener hasn't echoed.
- **Setup:** create note via repo; immediately `NoteStore.clear()` to
  drop the in-memory mirror; do **not** reattach the listener.
- **Assert:** `repo.loadNoteLinesAwait(noteId)` resolves successfully
  within a bounded time and returns real ids (timeout path → Firestore
  one-shot read).

### Phase 1 — schema fields

#### IT-1a `save stamps version, lastWriterOpId, containedNotesBase`
- **Why emulator:** real Firestore doc state; type round-trip via
  `noteFromFirestore`.
- **Setup:** seed note; edit; save.
- **Assert:** read raw doc via `Firestore.collection("notes").document(id)`;
  fields populated. Version increments on second save; `lastWriterOpId`
  changes per save; `containedNotesBase` matches the local snapshot at
  save start.

### Phase 2 — atomic batched multi-note save

#### IT-2a `saveMultipleNotes commits all docs in one batch`
- **Why emulator:** real `WriteBatch.commit()` semantics.
- **Setup:** seed two roots + two children. Edit both. Call
  `saveMultipleNotes(items)`.
- **Assert:** post-commit, all four docs reflect the new state. The four
  docs share the same `lastWriterOpId` (proves they came from one batch).

#### IT-2b `saveMultipleNotes rolls back when one item trips the content-drop guard`
- **Why emulator:** unit test mocks the throw; emulator confirms the
  pre-commit assertion blocks the batch before any doc is written.
- **Setup:** seed root with 4 children; build a SaveItem for it that drops
  3 children (trips the guard); pair with a benign second item.
- **Assert:** `saveMultipleNotes` returns failure with `ContentDropAbortException`;
  no docs in the emulator changed (read each doc, confirm `version` and
  `containedNotes` unchanged from seed).

### Phase 3 — echo suppression (highest-value emulator coverage)

#### IT-3a `editor doesn't reload during own-save echo window`
- **Why emulator:** the entire purpose of the `clientOpId` registry is to
  suppress reloads from our own server echoes. Mocked listeners can't
  reproduce the timing.
- **Setup:** Compose test. Open a note. Type "hello". Trigger save.
  While save is in flight, type "world". Wait for the snapshot listener
  to deliver the echo.
- **Assert:** editor content is `"helloworld"` (not `"hello"` or some
  reverted state). `NoteStore.changedNoteIds` did **not** emit for this
  note id during the echo (verify via flow collector with timeout).

#### IT-3b `concurrent client's write does NOT match our own clientOpId`
- **Why emulator:** real second client's writes carry a different
  `lastWriterOpId`, so the listener should NOT suppress them.
- **Setup:** primary client loads a note. Secondary `FirebaseApp` writes
  to the same doc directly. Wait for snapshot.
- **Assert:** primary client's `NoteStore.changedNoteIds` emits the note
  id; the editor reload path runs (or the dirty-guard path, if applicable).

### Phase 4 + 9 — 3-way merge (root + per-descendant)

#### IT-4a `concurrent root-level additions both survive`
- **Why emulator:** two real clients mutating the same root's
  `containedNotes`.
- **Setup:** seed root with [c1]. Both clients load, both have
  `localBases[root] = [c1]`. Client A's save writes
  `containedNotes=[c1, c2]`. Client B's save writes `containedNotes=[c1, c3]`.
- **Assert:** final remote `root.containedNotes` contains all of c1, c2, c3
  (regardless of order). Neither client's child was orphaned.

#### IT-4b `concurrent root-level removal is honored`
- **Why emulator:** validates the merge respects remote removals across
  real listener-mediated reads.
- **Setup:** seed root with [c1, c2]. Client A loads. Client B removes
  c1 (saves with `containedNotes=[c2]`). Client A's listener picks up
  the change. Client A then saves an unrelated edit.
- **Assert:** post-A-save, `root.containedNotes == [c2]` (not restored).

#### IT-9a `concurrent additions to a deeply-nested descendant survive`
- **Why emulator:** Phase 9's per-descendant merge in real-time.
- **Setup:** seed root → c1 → [g1]. Both clients load.
  Client A adds g2 under c1. Client B adds g3 under c1.
- **Assert:** final `c1.containedNotes` contains g1, g2, g3.

#### IT-9b `concurrent subtree under a descendant isn't soft-deleted`
- **Why emulator:** validates `findConcurrentSubtree` extension under
  real timing.
- **Setup:** seed root → c1 → [g1]. Client A loads (caches descendantBases
  = {c1: [g1]}). Client B adds g2 under c1, with sub-children. Client A
  saves an unrelated change.
- **Assert:** g2 + its children remain `state == null` (not soft-deleted)
  after A's save.

### Phase 5 — cross-note cut/paste

#### IT-5a `cut from A then paste into B preserves doc identity`
- **Why emulator:** full round-trip with reload from listener.
- **Setup:** seed note A with child c1; seed note B (empty). Cut c1 from
  A, save A. Paste c1 into B, save B.
- **Assert:** final remote state: `A.containedNotes == []`, c1 has
  `parentNoteId == B`, `state == null`. B's `containedNotes` includes c1.

#### IT-5b `unreclaimed cut survives as cut-delete; recoverable`
- **Why emulator:** `state='cut-delete'` round-trips through the listener;
  emulator confirms reconstruction filters it out.
- **Setup:** seed A with c1. Cut c1, save A (no paste).
- **Assert:** raw doc c1 has `state == 'cut-delete'`. `noteStore.getNoteLinesById(A)`
  no longer reflects c1. The doc is still readable for a Recover-style query.

#### IT-5c `paste reclaims a cut-delete doc rather than allocating fresh`
- **Why emulator:** identity preservation across real listener round-trip.
- **Setup:** seed A with c1. Cut c1, save A → c1 is `state='cut-delete'`.
  Reload listener. Paste c1 into B, save B.
- **Assert:** c1's doc id matches the original (not a fresh id);
  `state == null`, `parentNoteId == B`.

#### IT-5d `view directive: cut from bottom of one embedded note, paste at top of another, saveAll`
- **Why emulator:** exercises Phase 5 cross-note identity through
  `InlineEditSession`s populated by `ensureSessionsForNotes`, plus
  Phase 2 atomic multi-note save (saveAll combines main + inline
  sessions into one batch). Real listener round-trip + real reclaim.
- **Setup:** seed three target notes (A, B, C) each with two child lines
  ([a1, a2], [b1, b2], [c1, c2]). Seed a host note whose content has a
  view directive that matches A, B, C. Open the host note: directive
  evaluation populates inline sessions for A, B, C. From session A, cut
  line `a2` (the bottom). Switch focus to session C and paste at the top
  (above c1). saveAll.
- **Assert:**
  - On the wire: `a2`'s doc id is unchanged (not a fresh allocation).
  - `a2.parentNoteId == C`, `a2.state == null`.
  - `A.containedNotes == [a1]`, `C.containedNotes == [a2, c1, c2]`.
  - All three notes' writes share the same `lastWriterOpId` (proves
    one batch).
  - Reload via `repo.loadNoteLinesAwait` for each note; line content
    matches; no soft-deletes for `a2`.

### Async-only fix

#### IT-Aa `view-directive ensureSessionsForNotes awaits listener for newly-created notes`
- **Why emulator:** validates the awaitNoteLoaded primitive against real
  listener delivery.
- **Setup:** create note N via repo. Without explicit wait, immediately
  call `loadNoteLinesAwait(N.id)`.
- **Assert:** resolves with real lines (no synth, no null ids); the
  awaitPendingSave fast-path was hit (via inspection of timing or counters).

#### IT-Ab `loadNoteLinesAwait falls back to Firestore on listener timeout`
- **Why emulator:** the timeout → repo fallback path fires only with real
  Firestore.
- **Setup:** clear the listener (so it can never deliver), keep the
  pending-save tracker empty. Call `loadNoteLinesAwait(existingNoteId)`.
- **Assert:** resolves successfully (within ~2× `NOTE_STORE_AWAIT_MS`)
  via the Firestore one-shot read; lines have real ids.

### Persistence + offline

#### IT-Pa `offline edits survive process restart and sync on reconnect`
- **Why emulator:** Firestore client persistence path is real (uses
  IndexedDB-like SQLite cache).
- **Setup:** seed a note. Disable network on the Firestore client.
  Edit and save. Kill process; relaunch (test framework permitting via
  `recreateActivity()` or restarting the test process). Re-enable network.
- **Assert:** the queued save eventually commits to the emulator (poll
  the doc for the new content). No data loss.

### Type-tightening regression coverage

#### IT-Ta `RecoverScreen-equivalent flow uses sentinels, not nulls`
- **Why emulator:** Android doesn't have a Recover screen, but the Phase 0
  identity contract still applies on Android session-init paths.
  This guards against a future regression where someone passes
  `noteId = null` into a `NoteLine`.
- **Setup:** any save flow that involves new descendant lines (e.g. typing
  several lines in a fresh note).
- **Assert:** the `NoteLine.noteId` of fresh descendant lines is a sentinel
  (matches `NoteIdSentinel.isSentinel(...)`); never null. (Also a unit-test
  invariant via the type system; emulator value is the end-to-end check.)

---

## Test infrastructure to add

### `EmulatorTestSupport` extensions

```kotlin
/** Resets NoteStore + repo to a clean state between tests. */
fun resetEmulatorState() {
    NoteStore.clear()
    // Optional: clear the emulator's Firestore data via the admin REST API
    // so each test starts from empty.
}

/** Starts a second FirebaseApp + Firestore client to simulate a second
 *  device. Use `secondaryRepo` to issue writes that the primary client
 *  receives via its listener. */
class SecondaryClient {
    val firestore: FirebaseFirestore
    val auth: FirebaseAuth
    val repo: NoteRepository
}
fun newSecondaryClient(name: String = "test-secondary"): SecondaryClient

/** Wait for [predicate] to be true within [timeoutMs], polling. Already
 *  exists for compose; provide a non-Compose variant. */
suspend fun waitFor(timeoutMs: Long = 5000, predicate: () -> Boolean)

/** Read a doc directly from the emulator, bypassing NoteStore. Useful
 *  for "what's actually on the wire" assertions. */
suspend fun readRawNote(noteId: String): Map<String, Any?>?

/** Force-emit a snapshot for a given note by writing+reverting a
 *  no-op field. (For tests that need to nudge the listener without
 *  semantic state change.) */
suspend fun forceSnapshot(noteId: String)
```

### Two-client harness

The trickiest piece. Use `FirebaseApp.initializeApp(context, options, "secondary")`
to spin up a second `FirebaseApp` instance. Bind a separate
`FirebaseFirestore.getInstance(secondaryApp)` and `FirebaseAuth` to it.
Sign in as a separate anonymous user (so security rules treat them as
distinct uids, but they share the same workspace via shared `userId`
field assertions if we test rules — or share the same uid by reusing the
auth, which is fine for save-path tests).

For tests that don't need a second auth identity (most of the merge tests),
the simpler approach is: write directly via the same primary client's
Firestore handle but **bypass the local NoteRepository pipeline** so the
listener treats it as an external write. Pseudocode:

```kotlin
// "Other client" appended c2 — write it directly, skipping the
// NoteRepository.planSave path so it doesn't carry our clientOpId.
val ref = firestore.collection("notes").document(c2Id)
ref.set(mapOf(
    "userId" to userId,
    "content" to "c2",
    "parentNoteId" to rootId,
    "rootNoteId" to rootId,
    "containedNotes" to emptyList<String>(),
    "version" to 1,
    "lastWriterOpId" to "external_${UUID.randomUUID()}",
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
)).await()
firestore.collection("notes").document(rootId)
    .update("containedNotes", FieldValue.arrayUnion(c2Id), "version", FieldValue.increment(1)).await()
```

Cleaner than spinning up a second `FirebaseApp` and adequate for every
merge-conflict test in this plan.

---

## Sequencing

**P0 — Highest signal, blocks confidence in production:**
1. IT-3a (echo suppression during typing) — the most expensive bug if it
   regresses; impossible to catch in unit tests.
2. IT-5a (cut/paste round-trip) — full Phase 5 cross-note identity flow.
3. IT-Aa (loadNoteLinesAwait pending-save fast path).

**P1 — Behavior verification for high-traffic phases:**
4. IT-2a (atomic multi-note batch).
5. IT-4a (concurrent root-level additions merge).
6. IT-9a (concurrent descendant additions merge).
7. IT-1a (schema fields stamped correctly).

**P2 — Edge cases worth pinning:**
8. IT-2b (atomic rollback on guard trip).
9. IT-3b (other-client write isn't suppressed).
10. IT-4b (concurrent removal honored).
11. IT-5b, IT-5c (cut-delete state + reclaim).
12. IT-9b (subtree-under-descendant survives).
13. IT-Ab (timeout fallback to Firestore read).

**P3 — Nice-to-have, complex setup:**
14. IT-Pa (offline persistence + restart). Requires test-process restart
    plumbing; do this only if we hit a real regression in the offline path.
15. IT-Ta (sentinel-not-null guard) — mostly redundant given the type
    system, but worth one assertion to anchor the contract.

Total: 15 tests. P0+P1 (7 tests) is the must-have set. P0 alone is the
"if regressions slip past unit tests, these catch them" tripwire.

## Out of scope

- **Network-flake fuzzing** — emulator simulates timing but not packet
  loss; trust Firestore's own retry tests.
- **Security rules** — `firestore.rules.emulator` is permissive for
  signed-in users; rule tests belong in their own suite against
  `firestore.rules` (production rules).
- **Performance / scaling** — see `docs/scaling-risks.md` for the
  workload-size concerns; those need real-data benchmarks, not emulator
  tests.
- **UI flows beyond echo suppression** — existing `CreateNoteFlowTest`,
  `EditExistingLineFlowTest`, `EditorSaveFlowTest` already cover Compose
  paths; this plan adds data-layer coverage on top, not a replacement.

## Process for keeping this plan honest

When a unit test would have to mock something specific to Firestore's
listener / batch / persistence behavior to be meaningful, that test
belongs in this emulator suite instead. Add it here with an `IT-N` id and
a one-paragraph "why emulator" justification, then implement.

When a regression is caught only after deploy and the post-mortem points
at "the unit test mock didn't match real Firestore," promote the
scenario from the post-mortem into a new emulator test before closing
the bug.
