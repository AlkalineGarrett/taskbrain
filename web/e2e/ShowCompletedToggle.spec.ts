import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, unique, waitForNoteInStore } from './support'

/**
 * Mirrors `ShowCompletedToggleTest.kt`.
 *
 * Seed a note with one checked line → open it → toggle "Show completed"
 * via the editor's overflow menu → assert the checked line is hidden and
 * a "(N completed)" placeholder appears → toggle again → assert restored.
 *
 * The checkbox prefix `☑ ` matches `CHECKBOX_CHECKED` from LinePrefixes.ts.
 */
test.describe('ShowCompletedToggle', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('toggle hides and re-shows checked line', async ({ page }) => {
    await gotoApp(page)

    const title = unique('title')
    const checkedTail = unique('checked')
    const seedId = await seedMultiLineNote(page, `${title}\n☑ ${checkedTail}`)
    await waitForNoteInStore(page, seedId)

    await page.getByRole('button', { name: 'All notes' }).click()
    await page.getByRole('button', { name: title }).click()

    // Editor is open, checked line's textarea contains the tail.
    await expect.poll(async () =>
      (await page.locator('textarea').evaluateAll((els) =>
        (els as HTMLTextAreaElement[]).map((el) => el.value),
      )),
    ).toEqual(expect.arrayContaining([expect.stringContaining(checkedTail)]))

    // Open the CommandBar overflow menu (⋮) and toggle "Show completed".
    // The trigger renders "⋮" as text and sets title="Note menu", so the
    // accessible-name resolves to "⋮" — use getByTitle for reliability.
    const commandBarMenu = page.getByTitle('Note menu').first()
    await commandBarMenu.click()
    await page.getByRole('button', { name: 'Show completed' }).click()

    // Checked line hidden, "(1 completed)" placeholder visible.
    await expect.poll(async () =>
      (await page.locator('textarea').evaluateAll((els) =>
        (els as HTMLTextAreaElement[]).map((el) => el.value),
      )),
    ).not.toEqual(expect.arrayContaining([expect.stringContaining(checkedTail)]))
    await expect(page.getByText('(1 completed)')).toBeVisible()

    // Toggle back — checked line returns.
    await commandBarMenu.click()
    await page.getByRole('button', { name: 'Show completed' }).click()
    await expect.poll(async () =>
      (await page.locator('textarea').evaluateAll((els) =>
        (els as HTMLTextAreaElement[]).map((el) => el.value),
      )),
    ).toEqual(expect.arrayContaining([expect.stringContaining(checkedTail)]))
  })
})
