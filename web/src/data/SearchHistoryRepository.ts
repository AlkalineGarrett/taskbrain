import {
  collection,
  addDoc,
  getDocs,
  query,
  orderBy,
  limit,
  type Firestore,
} from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import { firestoreUsage } from './FirestoreUsage'
import { noteStore } from './NoteStore'

export interface SearchHistoryEntry {
  query: string
  criteria: Record<string, boolean>
  timestamp: number
}

const MAX_ENTRIES = 10
const LOCAL_KEY = 'search_history'

export class SearchHistoryRepository {
  constructor(
    private readonly db: Firestore,
    private readonly auth: Auth,
  ) {}

  private historyCollection(userId: string) {
    return collection(this.db, 'users', userId, 'searchHistory')
  }

  getHistory(): SearchHistoryEntry[] {
    return this.loadLocal().sort((a, b) => b.timestamp - a.timestamp)
  }

  saveEntry(entry: SearchHistoryEntry): void {
    const local = this.loadLocal().filter(
      (e) => !(e.query === entry.query && criteriaEqual(e.criteria, entry.criteria)),
    )
    local.unshift(entry)
    const trimmed = local.slice(0, MAX_ENTRIES)
    this.saveLocal(trimmed)

    const userId = this.auth.currentUser?.uid
    if (!userId) return
    const col = this.historyCollection(userId)
    addDoc(col, {
      query: entry.query,
      criteria: entry.criteria,
      timestamp: entry.timestamp,
    }).then(
      () => firestoreUsage.recordWrite('saveSearchHistory', 'SET'),
      (err) => {
        console.error('SearchHistoryRepository.saveEntry failed', err)
        noteStore.raiseWarning(
          'Search history sync failed. Your local search history is still available, but recent searches may not appear on other devices.',
        )
      },
    )
  }

  async syncFromFirebase(): Promise<void> {
    const userId = this.auth.currentUser?.uid
    if (!userId) return
    try {
      const col = this.historyCollection(userId)
      const q = query(col, orderBy('timestamp', 'desc'), limit(MAX_ENTRIES))
      const snapshot = await getDocs(q)
      firestoreUsage.recordRead('syncSearchHistory', 'GET_DOCS', snapshot.size)

      const remote: SearchHistoryEntry[] = snapshot.docs
        .map((doc) => {
          const data = doc.data()
          return {
            query: data.query as string,
            criteria: (data.criteria as Record<string, boolean>) ?? {},
            timestamp: (data.timestamp as number) ?? 0,
          }
        })
        .filter((e) => e.query && e.timestamp)

      const local = this.loadLocal()
      const merged = mergeHistories(local, remote)
      this.saveLocal(merged)
    } catch (err) {
      console.error('SearchHistoryRepository.syncFromFirebase failed', err)
      noteStore.raiseWarning(
        'Search history sync failed. Recent searches from other devices may not appear.',
      )
    }
  }

  private loadLocal(): SearchHistoryEntry[] {
    try {
      const json = localStorage.getItem(LOCAL_KEY)
      if (!json) return []
      return JSON.parse(json) as SearchHistoryEntry[]
    } catch {
      return []
    }
  }

  private saveLocal(entries: SearchHistoryEntry[]): void {
    try {
      localStorage.setItem(LOCAL_KEY, JSON.stringify(entries))
    } catch {
      // localStorage may be unavailable
    }
  }
}

function criteriaEqual(a: Record<string, boolean>, b: Record<string, boolean>): boolean {
  const keysA = Object.keys(a)
  const keysB = Object.keys(b)
  if (keysA.length !== keysB.length) return false
  return keysA.every((k) => a[k] === b[k])
}

function mergeHistories(
  local: SearchHistoryEntry[],
  remote: SearchHistoryEntry[],
): SearchHistoryEntry[] {
  const byKey = new Map<string, SearchHistoryEntry>()
  for (const entry of [...local, ...remote]) {
    const key = `${entry.query}|${JSON.stringify(entry.criteria)}`
    const existing = byKey.get(key)
    if (!existing || entry.timestamp > existing.timestamp) {
      byKey.set(key, entry)
    }
  }
  return [...byKey.values()]
    .sort((a, b) => b.timestamp - a.timestamp)
    .slice(0, MAX_ENTRIES)
}
