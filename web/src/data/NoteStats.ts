import { Timestamp } from 'firebase/firestore'

/** IMPORTANT: Keep in sync with Kotlin NoteStats.kt and docs/schema.md */
export interface NoteStats {
  lastAccessedAt: Timestamp | null
  viewCount: number
  /** YYYY-MM-DD keys in the device's local timezone, so a midnight crossing reads as the user-perceived calendar day. */
  viewedDays: Record<string, boolean>
}

export function noteStatsFromFirestore(data: Record<string, unknown>): NoteStats {
  return {
    lastAccessedAt: (data.lastAccessedAt as Timestamp) ?? null,
    viewCount: (data.viewCount as number) ?? 0,
    viewedDays: (data.viewedDays as Record<string, boolean>) ?? {},
  }
}
