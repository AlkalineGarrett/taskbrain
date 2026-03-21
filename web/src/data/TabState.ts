import type { RecentTab } from './RecentTabsRepository'

const MAX_TABS = 5
const MAX_DISPLAY_LENGTH = 12
const ALARM_SYMBOL = '⏰'
const ALARM_DIRECTIVE_REGEX = /\[alarm\("[^"]+"\)]/g

/**
 * Moves an existing tab to the front, updating its display text.
 * If the tab doesn't exist, adds it to the front (capped at MAX_TABS).
 */
export function addOrUpdateTabState(
  tabs: RecentTab[],
  noteId: string,
  displayText: string,
): RecentTab[] {
  const existing = tabs.find((t) => t.noteId === noteId)
  if (existing) {
    const updated = { ...existing, displayText }
    return [updated, ...tabs.filter((t) => t.noteId !== noteId)]
  }
  const newTab: RecentTab = { noteId, displayText, lastAccessedAt: null }
  return [newTab, ...tabs].slice(0, MAX_TABS)
}

/**
 * Updates the display text for a specific tab without reordering.
 */
export function updateDisplayTextState(
  tabs: RecentTab[],
  noteId: string,
  displayText: string,
): RecentTab[] {
  return tabs.map((t) => (t.noteId === noteId ? { ...t, displayText } : t))
}

/**
 * Removes a tab and determines where to navigate.
 * Returns the new tab list and the noteId to navigate to (null = go home).
 */
export function removeTabState(
  tabs: RecentTab[],
  noteId: string,
  currentNoteId: string | undefined,
): { tabs: RecentTab[]; navigateTo?: string | null } {
  const closedIndex = tabs.findIndex((t) => t.noteId === noteId)
  const remaining = tabs.filter((t) => t.noteId !== noteId)

  if (noteId !== currentNoteId) {
    return { tabs: remaining }
  }

  const nextTab = remaining[closedIndex] ?? remaining[closedIndex - 1]
  return { tabs: remaining, navigateTo: nextTab?.noteId ?? null }
}

/**
 * Extracts display text from note content.
 * Cleans the first line by removing alarm symbols and truncating.
 */
export function extractDisplayText(content: string): string {
  const firstLine = content.split('\n')[0] ?? ''
  const cleaned = firstLine.replaceAll(ALARM_SYMBOL, '').replace(ALARM_DIRECTIVE_REGEX, '').trim()
  if (cleaned.length > MAX_DISPLAY_LENGTH) {
    return cleaned.slice(0, MAX_DISPLAY_LENGTH - 1) + '…'
  }
  return cleaned || '(empty)'
}
