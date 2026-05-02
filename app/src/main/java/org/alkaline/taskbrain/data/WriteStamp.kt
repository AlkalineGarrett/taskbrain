package org.alkaline.taskbrain.data

import java.util.UUID

const val FIELD_LAST_WRITER_OP_ID = "lastWriterOpId"
const val FIELD_VERSION = "version"

/**
 * Per-write fields that tag a Firestore write so the listener can identify
 * our own server echo and skip the editor reload that would otherwise
 * clobber characters typed since the save started. `version` is bumped
 * monotonically (new docs start at 1).
 *
 * Use from any direct Firestore write site that bypasses
 * `NoteRepository.withPendingOp` (DSL ops, once-cache flush, settings
 * updates). Wrap the write in [withStampedWrite] so the opId is also
 * registered with [NoteStore] for echo suppression.
 */
fun stampWrite(opId: String, existing: Note?): Map<String, Any> = mapOf(
    FIELD_LAST_WRITER_OP_ID to opId,
    FIELD_VERSION to ((existing?.version ?: 0L) + 1L),
)

fun newClientOpId(): String = UUID.randomUUID().toString()

/**
 * Register a fresh `clientOpId` with [NoteStore], invoke [block] with it +
 * a stamp map ready to splice into the write payload, and release the
 * pending-op registration on completion. Use this from sites that don't
 * go through `NoteRepository.withPendingOp`.
 */
suspend fun <T> withStampedWrite(
    existing: Note?,
    block: suspend (opId: String, stamp: Map<String, Any>) -> T,
): T {
    val opId = newClientOpId()
    NoteStore.registerPendingOp(opId)
    return try {
        block(opId, stampWrite(opId, existing))
    } finally {
        NoteStore.releasePendingOp(opId)
    }
}
