import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, unique, waitForNoteInStoreContaining, waitForSaved } from './support'

/**
 * Mirrors `EditorSaveFlowTest.kt`.
 *
 * Click "Add note" → editor opens with auto-focused empty line → type
 * → click "Save" → status flips to "Saved" → navigate back to "All
 * notes" → typed content appears as a row.
 */
test.describe('EditorSaveFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('Add note → type → save → row appears in list', async ({ page }) => {
    await gotoApp(page)

    await page.getByRole('button', { name: 'Add note' }).click()

    // Editor mounts on /note/:id; the first line's textarea is auto-focused.
    await expect(page).toHaveURL(/\/note\/[^/]+$/)
    const firstLine = page.locator('textarea').first()
    await expect(firstLine).toBeFocused()

    const typed = unique('typed')
    await page.keyboard.type(typed)
    await expect.poll(async () => firstLine.inputValue()).toBe(typed)

    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await waitForSaved(page)
    // The listener reflects the post-save content asynchronously; wait
    // for it to land in the store before navigating to the list.
    await waitForNoteInStoreContaining(page, typed)

    // Navigate to list and confirm the typed content rendered as a row.
    await page.getByRole('button', { name: 'All notes' }).click()
    await expect(page.getByRole('button', { name: typed })).toBeVisible()
  })
})
