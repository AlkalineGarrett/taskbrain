# Data layer (Android)

Repositories (`NoteRepository`, `AlarmRepository`, etc.), the snapshot listener cache (`NoteStore`), the schema (`Note.kt`, `NoteState.kt`), and reconstruction helpers (`NoteReconstruction.kt`).

## Cross-platform principles (load these too)

@docs/firestore-efficiency.md
@docs/live-cross-platform-sync.md
@docs/cross-platform-parity.md

## Android-specific notes

**Snapshot parser** — `NoteStore.parseNote` routes through Firestore's POJO mapper via `doc.toObject(Note::class.java)?.copy(id = doc.id)`, so new fields on `Note` are picked up automatically by reflection. Don't reintroduce a hand-rolled `Map<String, Any>` extractor here — the schema and the parser silently drift apart, and the symptom is "deleted-parent reconstruction shows only the first line + Fix button" (we hit this once with `deletionBatchId`).

**Soft-delete batches** — `softDeleteNote` and the editor save path stamp a `deletionBatchId` of the form `<source>_<uuid>` (see `DeletionSource.kt`). The reconstruction filter accepts same-batch deleted children when the parent itself is deleted (so a deleted note can still be viewed with its structure intact); older-deleted descendants stay excluded because their batchId differs. `undeleteNote` reads the root's authoritative state from Firestore (not NoteStore — the listener can lag), then restores only descendants whose `deletionBatchId` matches.
