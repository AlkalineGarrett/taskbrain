import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, unique, waitForNoteInStore } from './support'

/**
 * Mirrors `OpenNoteFromListTest.kt`.
 *
 * Seed a note, click its row in "All notes", verify the editor opens
 * with the seeded content visible (each line is its own <textarea>).
 */
test.describe('OpenNoteFromList', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('clicking a note row opens the editor with content', async ({ page }) => {
    await gotoApp(page)

    const tag = unique('open')
    const second = unique('child')
    const seedId = await seedMultiLineNote(page, `${tag}\n${second}`)
    await waitForNoteInStore(page, seedId)

    await page.getByRole('button', { name: 'All notes' }).click()
    await page.getByRole('button', { name: tag }).click()

    // URL switched to /note/{id}, and the editor's textareas carry the
    // seeded content (each line is a separate <textarea>, prefixes live
    // in sibling spans — so check `inputValue`, not innerText).
    await expect(page).toHaveURL(new RegExp(`/note/${seedId}$`))
    await expect.poll(async () => {
      const values = await page.locator('textarea').evaluateAll((els) =>
        (els as HTMLTextAreaElement[]).map((el) => el.value),
      )
      return values
    }).toEqual(expect.arrayContaining([tag, second]))
  })
})
