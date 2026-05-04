import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, unique, waitForNoteInStore, waitForNoteInStoreContaining, waitForSaved } from './support'

/**
 * Mirrors `EditExistingLineFlowTest.kt`.
 *
 * Seed a 4-line note → open it → type into the auto-focused (first) line
 * → save → leave + reopen via "All notes" → assert the typed marker is
 * still present and no original lines were lost.
 *
 * Each original line carries a unique tail so substring-matching in the
 * assertions survives the inserted edit marker on the title line.
 */
test.describe('EditExistingLineFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('edit a line, save, round-trip preserves all lines', async ({ page }) => {
    await gotoApp(page)

    const titleTail = unique('title')
    const firstChild = unique('first')
    const secondChild = unique('second')
    const thirdChild = unique('third')
    const seedId = await seedMultiLineNote(
      page,
      `${titleTail}\n${firstChild}\n${secondChild}\n${thirdChild}`,
    )
    await waitForNoteInStore(page, seedId)

    await page.getByRole('button', { name: 'All notes' }).click()
    await page.getByRole('button', { name: titleTail }).click()

    // Wait until all four lines are rendered as textareas.
    await expect.poll(async () =>
      (await page.locator('textarea').evaluateAll((els) =>
        (els as HTMLTextAreaElement[]).map((el) => el.value),
      )),
    ).toEqual(expect.arrayContaining([titleTail, firstChild, secondChild, thirdChild]))

    // Auto-focused first line gets the inserted edit marker.
    const editMarker = unique('edit')
    const firstLine = page.locator('textarea').first()
    await expect(firstLine).toBeFocused()
    // Type at the start of the line (cursor lands at position 0 on focus).
    await page.keyboard.type(editMarker)
    await expect.poll(async () => firstLine.inputValue()).toContain(editMarker)
    await expect.poll(async () => firstLine.inputValue()).toContain(titleTail)

    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await waitForSaved(page)
    // Wait for the post-save content to land in the store before navigating.
    await waitForNoteInStoreContaining(page, editMarker)

    // Round-trip: leave, reopen by titleTail (which survives the prepend).
    await page.getByRole('button', { name: 'All notes' }).click()
    await page.getByRole('button', { name: new RegExp(titleTail) }).click()

    // Wait for all four lines to render after the round-trip (the editor
    // mounts asynchronously, so reading textareas immediately after click
    // races the render). Then assert: one line has both titleTail and
    // editMarker; the other three child tails are intact.
    await expect.poll(async () =>
      page.locator('textarea').evaluateAll((els) =>
        (els as HTMLTextAreaElement[]).map((el) => el.value),
      ),
    ).toEqual(expect.arrayContaining([
      expect.stringMatching(new RegExp(`(${editMarker}.*${titleTail}|${titleTail}.*${editMarker})`)),
      firstChild,
      secondChild,
      thirdChild,
    ]))
  })
})
