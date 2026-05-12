import {
  doc,
  serverTimestamp,
  setDoc,
  type Firestore,
} from 'firebase/firestore'
import { firestoreUsage } from './FirestoreUsage'

/**
 * Per-subsystem signal fields on `users/{uid}` that subsystem listeners
 * watch to trigger delta pulls. Each [Channel] owns its own timestamp field
 * on the same user doc; listeners observe their channel's field and ignore
 * others. Fire-and-forget — callers should not await.
 * See `docs/live-cross-platform-sync.md`.
 */
export type Channel = 'NOTES' | 'ALARMS'

const CHANNEL_FIELD: Record<Channel, string> = {
  NOTES: 'lastNoteChange',
  ALARMS: 'lastAlarmChange',
}

/** Field name used on `users/{uid}` for a given signal channel. */
export function channelFieldName(channel: Channel): string {
  return CHANNEL_FIELD[channel]
}

export const UserDocSignal = {
  /** Called after every successful write to the channel's collection. */
  bump(
    db: Firestore,
    userId: string,
    channels: Channel | Iterable<Channel>,
  ): Promise<void> {
    const list = typeof channels === 'string' ? [channels] : Array.from(channels)
    if (list.length === 0) return Promise.resolve()
    const fields: Record<string, unknown> = {}
    for (const c of list) fields[CHANNEL_FIELD[c]] = serverTimestamp()
    return setUserFields(db, userId, fields, `UserDocSignal.bump.${list.join('+')}`)
  },

  /** Create-on-login so signal listeners have a doc to attach to. */
  ensureExists(db: Firestore, userId: string): Promise<void> {
    return setUserFields(db, userId, { uid: userId }, 'UserDocSignal.ensureExists')
  },
}

async function setUserFields(
  db: Firestore,
  userId: string,
  fields: Record<string, unknown>,
  operation: string,
): Promise<void> {
  try {
    await setDoc(doc(db, 'users', userId), fields, { merge: true })
    firestoreUsage.recordWrite(operation, 'SET')
  } catch (e) {
    console.warn(`[UserDocSignal] ${operation}(userId=${userId}) failed`, e)
  }
}
