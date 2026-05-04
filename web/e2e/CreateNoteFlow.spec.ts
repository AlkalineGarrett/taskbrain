import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, unique, waitForNoteInStore } from './support'

/**
 * Mirrors `CreateNoteFlowTest.kt`.
 *
 * Seeds a multi-line note via `NoteRepository.createMultiLineNote` (the
 * same path the app uses), then asserts it appears in the "All notes"
 * list. Verifies the seed → list-render plumbing end-to-end through the
 * snapshot listener.
 */
test.describe('CreateNoteFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('seeded multi-line note appears in All notes list', async ({ page }) => {
    await gotoApp(page)

    const tag = unique('create')
    const seedId = await seedMultiLineNote(page, `${tag}\nLine 2\nLine 3`)
    await waitForNoteInStore(page, seedId)

    await page.getByRole('button', { name: 'All notes' }).click()

    // Note row in the list (the button rendering firstLineOf(content)).
    await expect(page.getByRole('button', { name: tag })).toBeVisible()
  })
})
