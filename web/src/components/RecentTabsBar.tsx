import { useEffect, useState, useCallback, useRef, useLayoutEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { RecentTabsRepository, type RecentTab } from '@/data/RecentTabsRepository'
import { addOrUpdateTabState, updateDisplayTextState, removeTabState } from '@/data/TabState'
import { db, auth } from '@/firebase/config'
import { EMPTY_TAB } from '@/strings'
import styles from './RecentTabsBar.module.css'

const repo = new RecentTabsRepository(db, auth)
const TAB_ANIMATION_MS = 250

/**
 * Module-level refs so external functions can snapshot positions
 * and update tabs state without prop drilling.
 */
let snapshotAndSetTabs: ((updater: (prev: RecentTab[]) => RecentTab[]) => void) | null = null
let setTabsNoAnimation: React.Dispatch<React.SetStateAction<RecentTab[]>> | null = null

export function RecentTabsBar() {
  const [tabs, setTabs] = useState<RecentTab[]>([])
  const navigate = useNavigate()
  const { noteId: currentNoteId } = useParams<{ noteId: string }>()

  // FLIP animation refs
  const tabRefs = useRef<Map<string, HTMLButtonElement>>(new Map())
  const prevPositions = useRef<Map<string, number>>(new Map())

  /** Snapshot current tab positions, then apply a state update. */
  const snapshotAndSetTabsLocal = useCallback((updater: (prev: RecentTab[]) => RecentTab[]) => {
    // Capture positions before React re-renders
    prevPositions.current = new Map()
    tabRefs.current.forEach((el, noteId) => {
      prevPositions.current.set(noteId, el.getBoundingClientRect().left)
    })
    setTabs(updater)
  }, [])

  // Expose to module-level functions
  useEffect(() => {
    snapshotAndSetTabs = snapshotAndSetTabsLocal
    setTabsNoAnimation = setTabs
    return () => {
      snapshotAndSetTabs = null
      setTabsNoAnimation = null
    }
  }, [snapshotAndSetTabsLocal])

  // Load tabs from Firestore on mount only (no animation)
  useEffect(() => {
    const loadTabs = async () => {
      try {
        const openTabs = await repo.getOpenTabs()
        setTabs(openTabs)
      } catch {
        // silently fail
      }
    }
    void loadTabs()
  }, [])

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
      setTabs((prev) => {
        const result = removeTabState(prev, noteId, currentNoteId)
        if (result.navigateTo !== undefined) {
          navigate(result.navigateTo ? `/note/${result.navigateTo}` : '/')
        }
        return result.tabs
      })
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
      {tabs.map((tab) => (
        <button
          key={tab.noteId}
          ref={(el) => setTabRef(tab.noteId, el)}
          className={`${styles.tab} ${tab.noteId === currentNoteId ? styles.active : ''}`}
          onClick={() => handleTabClick(tab.noteId)}
        >
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
      ))}
    </div>
  )
}

/** Call this when a note is opened. Moves/adds tab to front with animation. */
export async function addOrUpdateTab(noteId: string, displayText: string): Promise<void> {
  snapshotAndSetTabs?.((prev) => addOrUpdateTabState(prev, noteId, displayText))

  try {
    await repo.addOrUpdateTab(noteId, displayText)
  } catch {
    // silently fail
  }
}

/**
 * Removes a tab and returns the noteId to navigate to (null = go home).
 * Use when closing a tab programmatically (e.g., after deleting a note).
 */
export async function removeTab(noteId: string, currentNoteId: string | undefined): Promise<string | null> {
  let navigateTo: string | null = null
  await repo.removeTab(noteId)
  setTabsNoAnimation?.((prev) => {
    const result = removeTabState(prev, noteId, currentNoteId)
    navigateTo = result.navigateTo ?? null
    return result.tabs
  })
  return navigateTo
}

/** Call this when a note's title changes. Updates text without reordering (no animation). */
export async function updateTabDisplayText(noteId: string, displayText: string): Promise<void> {
  setTabsNoAnimation?.((prev) => updateDisplayTextState(prev, noteId, displayText))

  try {
    await repo.addOrUpdateTab(noteId, displayText)
  } catch {
    // silently fail
  }
}
