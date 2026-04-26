import { useEffect } from 'react'
import { addOrUpdateTab, updateTabDisplayText } from '@/components/RecentTabsBar'
import { extractDisplayText } from '@/data/TabState'

/**
 * Keeps the recent-tabs bar in sync with the currently-loaded note.
 * - Moves/adds the tab to front when the note opens.
 * - Updates the tab's display text when the title line changes (no reorder).
 */
export function useTabSync(
  noteId: string | null | undefined,
  loading: boolean,
  firstLineText: string,
): void {
  useEffect(() => {
    if (!noteId || loading) return
    localStorage.setItem('lastNoteId', noteId)
    const displayText = extractDisplayText(firstLineText)
    void addOrUpdateTab(noteId, displayText)
    // firstLineText intentionally omitted — only the initial value at open time
    // is used for the bring-to-front; subsequent edits flow through the second effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [noteId, loading])

  useEffect(() => {
    if (!noteId || loading) return
    const displayText = extractDisplayText(firstLineText)
    void updateTabDisplayText(noteId, displayText)
  }, [noteId, loading, firstLineText])
}
