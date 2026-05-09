# Data layer (web)

Repositories (`NoteRepository.ts`, etc.), the snapshot listener cache (`NoteStore.ts`), the schema (`Note.ts`, `NoteState.ts`), and reconstruction helpers (`NoteReconstruction.ts`).

## Cross-platform principles (load these too)

@docs/firestore-efficiency.md
@docs/live-cross-platform-sync.md
@docs/cross-platform-parity.md

## Web-specific notes

**Soft-delete batches** — `softDeleteNote` and the editor save path stamp a `deletionBatchId` of the form `<source>_<uuid>` (see `DeletionSource.ts`). The reconstruction filter accepts same-batch deleted children when the parent itself is deleted; older-deleted descendants stay excluded. `undeleteNote` reads the root's authoritative state from Firestore directly (not NoteStore — listeners can lag), then restores only descendants whose `deletionBatchId` matches via a `where('deletionBatchId', '==', rootBatchId)` query.

**`noteFromFirestore` parses every schema field explicitly.** When you add a field on `Note`, also add it here. (Compile won't catch a missing line because `noteFromFirestore` returns `Note` and any missing field falls back to its declared default.) Similar discipline holds on the Android side — see `app/src/main/java/org/alkaline/taskbrain/data/CLAUDE.md`.
