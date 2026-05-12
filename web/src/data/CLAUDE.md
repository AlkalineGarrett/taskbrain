# Data layer (web)

Repositories (`NoteRepository.ts`, etc.), the notes cache (`NoteStore.ts`, signal-listener + delta-pull — see `docs/note-sync-pull-migration.md`), the schema (`Note.ts`, `NoteState.ts`), and reconstruction helpers (`NoteReconstruction.ts`).

## Cross-platform principles (load these too)

@docs/firestore-efficiency.md
@docs/live-cross-platform-sync.md
@docs/cross-platform-parity.md

## Web-specific notes

**Soft-delete batches** — `softDeleteNote` and the editor save path stamp a `deletionBatchId` of the form `<source>_<uuid>` (see `DeletionSource.ts`). The reconstruction filter accepts same-batch deleted children when the parent itself is deleted; older-deleted descendants stay excluded. `undeleteNote` reads the root's authoritative state from Firestore directly (not NoteStore — listeners can lag), then restores only descendants whose `deletionBatchId` matches via a `where('deletionBatchId', '==', rootBatchId)` query.

**`noteFromFirestore` parses every schema field explicitly.** When you add a field on `Note`, also add it here. (Compile won't catch a missing line because `noteFromFirestore` returns `Note` and any missing field falls back to its declared default.) Similar discipline holds on the Android side — see `app/src/main/java/org/alkaline/taskbrain/data/CLAUDE.md`.

**Centralized signal bump** — every write path that touches `notes/{id}` must fire `UserDocSignal.bump(db, uid)` after the successful commit so other clients' NoteStores trigger a delta pull. `NoteRepository.commitInBatches` already does this for batched writes; `createNote`, `createMultiLineNote`, `updateShowCompleted`, `hardDeleteAllSoftDeleted`, and `NoteRepositoryOperations` (the DSL surface) do it inline. Adding a new write path means adding a bump — otherwise the change is invisible to other clients until they happen to foreground and trip the once-per-attach count() check.

**Hard delete is special.** The delta pull filter `updatedAt > lastSync` can't observe a vanished doc. `hardDeleteAllSoftDeleted` calls `noteStore.removeFromRawNotes(ids)` directly so the calling client's UI updates immediately; remote clients pick up the divergence at the next attach via the count() detection path.
