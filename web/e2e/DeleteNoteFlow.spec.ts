import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, syncNoteStore, unique, waitForNoteInStore } from './support'

/**
 * Mirrors `DeleteNoteFlowTest.kt`.
 *
 * Seed a note, open its row's overflow menu (⋮), click "Delete note",
 * verify it moves under the "Deleted notes" section header. The
 * confirmation dialog title from strings.ts ("Delete note?") needs
 * confirming before the soft-delete actually fires.
 */
test.describe('DeleteNoteFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('soft-deleted note moves to Deleted notes section', async ({ page }) => {
    await gotoApp(page)

    const tag = unique('delete')
    const seedId = await seedMultiLineNote(page, `${tag}\nchild`)
    await waitForNoteInStore(page, seedId)

    await page.getByRole('button', { name: 'All notes' }).click()
    const noteRow = page.getByRole('button', { name: tag })
    await expect(noteRow).toBeVisible()

    // Find the row's overflow menu (⋮) — it lives inside the same <li>
    // as the note button. The menu trigger has title="Note menu" and a
    // visible "⋮" glyph; the accessible name resolves to the glyph (not
    // the title attribute), so `getByTitle` is the reliable lookup.
    const noteListItem = page.locator('li', { has: noteRow })
    await noteListItem.getByTitle('Note menu').click()
    await page.getByRole('button', { name: 'Delete note' }).click()

    // The delete write is suppressed in the listener as our-own-echo, so
    // `reconstructedNotes` stays stale until a fresh initial snapshot.
    // Force a sync, then assert the row moved under "Deleted notes".
    await syncNoteStore(page)

    await expect(page.getByRole('heading', { name: 'Deleted notes' })).toBeVisible()
    await expect(page.getByRole('button', { name: tag })).toBeVisible()
  })
})
