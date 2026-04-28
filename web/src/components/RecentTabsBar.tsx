import { useEffect, useState, useCallback, useRef, useLayoutEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { recentTabsRepo as repo, type RecentTab } from '@/data/RecentTabsRepository'
import { computeDisplayTabs, extractDisplayText, nextNoteIdAfterRemove } from '@/data/TabState'
import { noteStore } from '@/data/NoteStore'
import { EMPTY_TAB, TAB_NEEDS_FIX_INDICATOR } from '@/strings'
import styles from './RecentTabsBar.module.css'
const TAB_ANIMATION_MS = 250

/**
 * Cross-references tab displayTexts with NoteStore content and writes
 * corrections back. Compensates for an old tab that hasn't been re-opened
 * since the note's title changed.
 */
function refreshDisplayTexts(tabs: RecentTab[]): RecentTab[] {
  return tabs.map((tab) => {
    const note = noteStore.getNoteById(tab.noteId)
    if (!note) return tab
    const freshDisplayText = extractDisplayText(note.content)
    if (freshDisplayText !== tab.displayText) {
      console.log(`[RecentTabs] refreshDisplayTexts: stale displayText for ${tab.noteId}: "${tab.displayText}" -> "${freshDisplayText}"`)
      void repo.updateTabDisplayText(tab.noteId, freshDisplayText)
      return { ...tab, displayText: freshDisplayText }
    }
    return tab
  })
}

interface RecentTabsBarProps {
  notesNeedingFix?: Set<string>
  /**
   * The note this device is currently viewing. Pinned at slot 0 of the bar
   * even when missing from the shared history (e.g. another device pushed it
   * past the 10-item buffer). Null when no note is loaded.
   */
  currentTab: { noteId: string; displayText: string } | null
}

export function RecentTabsBar({ notesNeedingFix, currentTab }: RecentTabsBarProps) {
  const [sharedTabs, setSharedTabs] = useState<RecentTab[]>([])
  const navigate = useNavigate()
  const { noteId: currentNoteId } = useParams<{ noteId: string }>()

  // FLIP animation refs
  const tabRefs = useRef<Map<string, HTMLButtonElement>>(new Map())
  const prevPositions = useRef<Map<string, number>>(new Map())

  // Subscribe to the listener-backed cache so external writes (this device's
  // own writes via local-echo, plus other devices' writes via server snapshot)
  // both update the bar without remount.
  useEffect(() => {
    const sync = () => {
      // Snapshot positions before React re-renders so the FLIP layout effect
      // can animate moves caused by the listener echo (e.g. another device
      // pushed a tab to the front).
      prevPositions.current = new Map()
      tabRefs.current.forEach((el, noteId) => {
        prevPositions.current.set(noteId, el.getBoundingClientRect().left)
      })
      setSharedTabs(refreshDisplayTexts(repo.getCachedTabs()))
    }
    // Pull whatever's already cached (warm listener after a remount); future
    // snapshots flow through the subscription. Listener-attach is fire-and-
    // forget — the first snapshot will fire `sync` again via `subscribe`.
    sync()
    const unsubscribe = repo.subscribe(sync)
    void repo.getOpenTabs()
    return unsubscribe
  }, [])

  const tabs = computeDisplayTabs(currentTab, sharedTabs)

  // FLIP animation: runs only when tabs changes
  useLayoutEffect(() => {
    if (prevPositions.current.size === 0) return

    const animations: Array<{ el: HTMLButtonElement; dx: number }> = []
    tabRefs.current.forEach((el, noteId) => {
      const prevLeft = prevPositions.current.get(noteId)
      if (prevLeft === undefined) return
      const currLeft = el.getBoundingClientRect().left
      const dx = prevLeft - currLeft
      if (Math.abs(dx) > 1) {
        animations.push({ el, dx })
      }
    })

    // Clear snapshot so subsequent non-tab re-renders don't animate
    prevPositions.current = new Map()

    if (animations.length === 0) return

    // Invert: place tabs at their old positions
    for (const { el, dx } of animations) {
      el.style.transform = `translateX(${dx}px)`
      el.style.transition = 'none'
    }

    // Play: animate to final positions
    requestAnimationFrame(() => {
      for (const { el } of animations) {
        el.style.transition = `transform ${TAB_ANIMATION_MS}ms ease`
        el.style.transform = ''
      }
    })
  }, [tabs])

  const handleTabClick = useCallback(
    (noteId: string) => {
      if (noteId === currentNoteId) return
      navigate(`/note/${noteId}`)
    },
    [currentNoteId, navigate],
  )

  const handleClose = useCallback(
    async (e: React.MouseEvent, noteId: string) => {
      e.stopPropagation()
      await repo.removeTab(noteId)
      if (noteId === currentNoteId) {
        const next = nextNoteIdAfterRemove(noteId, repo.getCachedTabs())
        navigate(next ? `/note/${next}` : '/')
      }
    },
    [currentNoteId, navigate],
  )

  const setTabRef = useCallback((noteId: string, el: HTMLButtonElement | null) => {
    if (el) {
      tabRefs.current.set(noteId, el)
    } else {
      tabRefs.current.delete(noteId)
    }
  }, [])

  if (tabs.length === 0) return null

  return (
    <div className={styles.bar}>
      {tabs.map((tab) => {
        const needsFix = notesNeedingFix?.has(tab.noteId) ?? false
        return (
          <button
            key={tab.noteId}
            ref={(el) => setTabRef(tab.noteId, el)}
            className={`${styles.tab} ${tab.noteId === currentNoteId ? styles.active : ''}`}
            onClick={() => handleTabClick(tab.noteId)}
          >
            {needsFix && (
              <span className={styles.needsFixIndicator} title={TAB_NEEDS_FIX_INDICATOR}>
                ⚠
              </span>
            )}
            <span className={styles.tabText}>
              {tab.displayText || EMPTY_TAB}
            </span>
            <span
              className={styles.closeButton}
              onClick={(e) => handleClose(e, tab.noteId)}
            >
              ×
            </span>
          </button>
        )
      })}
    </div>
  )
}

/**
 * Push a note to the front of the shared history. Skips the write when the
 * note is already at the front with the same display text — the listener's
 * read-your-write echo means the cache reflects this device's recent writes,
 * so the dedup check is reliable across remounts.
 */
export async function addOrUpdateTab(noteId: string, displayText: string): Promise<void> {
  const cached = await repo.getOpenTabs()
  if (cached[0]?.noteId === noteId && cached[0].displayText === displayText) return
  try {
    await repo.addOrUpdateTab(noteId, displayText)
  } catch {
    // silently fail
  }
}

/**
 * Remove a tab from the shared history. Returns the next noteId to navigate
 * to (next-most-recent in shared excluding the removed one), or null when
 * shared is empty and the caller should go home.
 *
 * Currently only used by note deletion, which is why navigation is always
 * required. The X-button in the bar handles its own navigation inline.
 */
export async function removeTab(removedNoteId: string): Promise<string | null> {
  await repo.removeTab(removedNoteId)
  return nextNoteIdAfterRemove(removedNoteId, repo.getCachedTabs())
}

/** Update a tab's display text without reordering. Skips no-op writes. */
export async function updateTabDisplayText(noteId: string, displayText: string): Promise<void> {
  const cached = await repo.getOpenTabs()
  const cachedExisting = cached.find((t) => t.noteId === noteId)
  if (cachedExisting && cachedExisting.displayText === displayText) return
  try {
    await repo.updateTabDisplayText(noteId, displayText)
  } catch {
    // silently fail
  }
}
