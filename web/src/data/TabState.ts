import type { RecentTab } from './RecentTabsRepository'

const MAX_DISPLAY_LENGTH = 12
const ALARM_SYMBOL = '⏰'
const ALARM_DIRECTIVE_REGEX = /\[alarm\("[^"]+"\)]/g
const RECURRING_ALARM_DIRECTIVE_REGEX = /\[recurringAlarm\("[^"]+"\)]/g

/** Tabs visible in the bar. The Firestore-backed history stores 2x this. */
export const MAX_DISPLAYED = 5

/**
 * Build the displayed tab list. The current tab (per-device) is pinned at
 * slot 0; the shared history fills the rest with the current tab deduped.
 *
 * The shared list is the same on every device, but each device's display
 * differs because each device's currentTab is local. With the current tab
 * not in the shared list (e.g. another device pushed it off the end of the
 * 10-item buffer), it still renders in slot 0 — we keep showing what the
 * user is actually viewing.
 *
 * The current tab only contributes display data (noteId, displayText) — no
 * lastAccessedAt is required, since the pinned slot doesn't sort.
 */
export function computeDisplayTabs(
  currentTab: { noteId: string; displayText: string } | null,
  sharedTabs: RecentTab[],
): RecentTab[] {
  if (!currentTab) return sharedTabs.slice(0, MAX_DISPLAYED)
  const rest = sharedTabs.filter((t) => t.noteId !== currentTab.noteId)
  const pinned: RecentTab = {
    noteId: currentTab.noteId,
    displayText: currentTab.displayText,
    lastAccessedAt: null,
  }
  return [pinned, ...rest].slice(0, MAX_DISPLAYED)
}

/**
 * After removing [removedNoteId] from the shared history, the noteId the
 * caller should navigate to (next-most-recent that isn't the removed one),
 * or null when nothing is left and the caller should go home.
 */
export function nextNoteIdAfterRemove(
  removedNoteId: string,
  sharedTabs: RecentTab[],
): string | null {
  return sharedTabs.find((t) => t.noteId !== removedNoteId)?.noteId ?? null
}

/**
 * Extracts display text from note content.
 * Cleans the first line by removing alarm symbols and truncating.
 */
export function extractDisplayText(content: string): string {
  const firstLine = content.split('\n')[0] ?? ''
  const cleaned = firstLine.replaceAll(ALARM_SYMBOL, '').replace(ALARM_DIRECTIVE_REGEX, '').replace(RECURRING_ALARM_DIRECTIVE_REGEX, '').trim()
  if (cleaned.length > MAX_DISPLAY_LENGTH) {
    return cleaned.slice(0, MAX_DISPLAY_LENGTH - 1) + '…'
  }
  return cleaned || '(empty)'
}
