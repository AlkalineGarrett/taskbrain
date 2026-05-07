import { test, expect, type Page, type Locator } from '@playwright/test'
import {
  clearFirestore,
  gotoApp,
  seedMultiLineNote,
  unique,
  waitForNoteInStore,
  waitForSaved,
  waitForNoteInStoreContaining,
} from './support'

/**
 * Cross-editor drag-drop in a view directive.
 *
 * Two notes A and B are embedded inline in a view note V via
 * `[view(find(name="..."))]` directives. The user drags a line out of A's
 * embedded editor and drops it into B's. The test verifies:
 *   1. The moved line appears in B's editor with its original Firestore noteId
 *      (without preservation it would get a paste sentinel and end up as a
 *      fresh doc on save).
 *   2. After save + reload, the line is in B with the same noteId.
 *   3. A single press of cmd+z reverts both halves of the move at once
 *      (UnifiedUndoManager's withGroup path).
 */
test.describe('CrossEditorDragDropFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('drag a line from one embedded note to another preserves id and undoes in one press', async ({ page }) => {
    await gotoApp(page)

    const aTitle = unique('a-title')
    const aLineToMove = unique('moved-line')
    const aTrailing = unique('a-trailing')
    const bTitle = unique('b-title')
    const bExistingLine = unique('b-existing')

    // A has a trailing line after aLineToMove so the gutter selection of
    // line 1 includes the line-terminator newline (see
    // SelectionCoordinates.shouldExtendSelectionToNewline). Without that
    // newline the cross-editor paste would inline-merge the moved text
    // into B's existing line instead of creating a new line.
    const aId = await seedMultiLineNote(page, `${aTitle}\n${aLineToMove}\n${aTrailing}`)
    const bId = await seedMultiLineNote(page, `${bTitle}\n${bExistingLine}`)
    await waitForNoteInStore(page, aId)
    await waitForNoteInStore(page, bId)

    // [view(find(name: X))] excludes the current note from results, so the
    // view note can find both A and B but not itself.
    const viewTitle = unique('view-title')
    const viewContent =
      `${viewTitle}\n` +
      `[view(find(name: "${aTitle}"))]\n` +
      `[view(find(name: "${bTitle}"))]`
    const viewId = await seedMultiLineNote(page, viewContent)
    await waitForNoteInStore(page, viewId)

    await page.goto(`/note/${viewId}`)
    await expect(page.getByRole('button', { name: 'All notes' })).toBeVisible({ timeout: 15_000 })

    const aLineRow = page.locator(`[data-view-note-id="${aId}"][data-view-line-index="1"]`)
    const bLineRow = page.locator(`[data-view-note-id="${bId}"][data-view-line-index="1"]`)
    await expect(aLineRow).toBeVisible()
    await expect(bLineRow).toBeVisible()

    // Wait for the line content to render — without this the textarea may
    // briefly show empty while the inline session populates from Firestore.
    await expect.poll(async () => aLineRow.locator('textarea').inputValue()).toBe(aLineToMove)
    await expect.poll(async () => bLineRow.locator('textarea').inputValue()).toBe(bExistingLine)

    // Capture the noteId for the line we'll move so we can prove it survives.
    const originalNoteId = await page.evaluate(async ({ id, content }) => {
      const mod = await import('/src/data/NoteStore.ts')
      const lines = mod.noteStore.getNoteLinesById(id)
      return lines?.find(l => l.content === content)?.noteId ?? null
    }, { id: aId, content: aLineToMove })
    expect(originalNoteId, 'aLine should have a real Firestore noteId before the move').toBeTruthy()

    await dragLineAcrossEditors(page, aLineRow, bLineRow)

    // After the drop:
    //   - A's section no longer holds aLineToMove
    //   - B's section now contains aLineToMove
    await expect.poll(async () =>
      page.locator(`[data-view-note-id="${aId}"] textarea`).evaluateAll(
        (els) => (els as HTMLTextAreaElement[]).map((el) => el.value),
      ),
    ).not.toContain(aLineToMove)
    await expect.poll(async () =>
      page.locator(`[data-view-note-id="${bId}"] textarea`).evaluateAll(
        (els) => (els as HTMLTextAreaElement[]).map((el) => el.value),
      ),
    ).toContain(aLineToMove)

    // The id-cell shows the noteIds of each line. The moved line in B should
    // display the original Firestore id, not a paste sentinel (which starts
    // with '@'). After the flat-tree refactor, the wrapper's structure is:
    //   <div data-view-note-id> → <div class="line" subgrid>
    //                              ↳ <div class="noteIdCell"> ← what we want
    //                              ↳ <div class="selectionGutter">
    //                              ↳ <div> (inner content wrapper)
    const movedLineIdCell = await page.evaluate(async ({ bIdArg, content }) => {
      const rows = Array.from(document.querySelectorAll<HTMLElement>(`[data-view-note-id="${bIdArg}"]`))
      for (const row of rows) {
        const ta = row.querySelector('textarea') as HTMLTextAreaElement | null
        if (ta?.value === content) {
          return row.querySelector('[class*="noteIdCell"]')?.textContent ?? null
        }
      }
      return null
    }, { bIdArg: bId, content: aLineToMove })
    expect(movedLineIdCell, 'moved line should display its original noteId').toBe(originalNoteId)

    // Save and round-trip: re-open and confirm the line is persisted in B
    // with the original id (not a freshly-allocated doc).
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await waitForSaved(page)
    await waitForNoteInStoreContaining(page, aLineToMove)

    const persistedLineNoteId = await page.evaluate(async ({ id, content }) => {
      const mod = await import('/src/data/NoteStore.ts')
      const lines = mod.noteStore.getNoteLinesById(id)
      return lines?.find(l => l.content === content)?.noteId ?? null
    }, { id: bId, content: aLineToMove })
    expect(persistedLineNoteId, 'after save, line should be in B with the original noteId').toBe(originalNoteId)

    // Single-press undo: cmd+z (or ctrl+z on non-mac) should revert both
    // halves of the cross-editor move together.
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control'
    await page.keyboard.press(`${modifier}+z`)

    await expect.poll(async () =>
      page.locator(`[data-view-note-id="${aId}"] textarea`).evaluateAll(
        (els) => (els as HTMLTextAreaElement[]).map((el) => el.value),
      ),
    ).toContain(aLineToMove)
    await expect.poll(async () =>
      page.locator(`[data-view-note-id="${bId}"] textarea`).evaluateAll(
        (els) => (els as HTMLTextAreaElement[]).map((el) => el.value),
      ),
    ).not.toContain(aLineToMove)
  })
})

/**
 * Custom mouse drag: select sourceRow's line via its gutter, then mouseDown
 * inside the selected content and drag to targetRow. Mirrors the real user
 * flow handled by useEditorLineMouse + useEditorInteractions.
 */
async function dragLineAcrossEditors(
  page: Page,
  sourceRow: Locator,
  targetRow: Locator,
): Promise<void> {
  // Step 1: click the source row's gutter to select the whole line.
  // CSS module class is hashed at build time; match by class*= prefix.
  const gutter = sourceRow.locator('[class*="selectionGutter"]').first()
  const gBox = await gutter.boundingBox()
  if (!gBox) throw new Error('source gutter not visible')
  await page.mouse.click(gBox.x + gBox.width / 2, gBox.y + gBox.height / 2)

  // Step 2: mouseDown inside the now-selected line, drag to target row's
  // text area, mouseUp to trigger the move.
  const sourceText = sourceRow.locator('textarea').first()
  const sBox = await sourceText.boundingBox()
  const tBox = await targetRow.boundingBox()
  if (!sBox || !tBox) throw new Error('row textareas not visible')

  // Anchor inside the selected content so handleMouseDown takes the move
  // branch (globalOffset within selection range).
  const startX = sBox.x + 30
  const startY = sBox.y + sBox.height / 2
  const endX = tBox.x + tBox.width / 2
  const endY = tBox.y + tBox.height / 2

  await page.mouse.move(startX, startY)
  await page.mouse.down()
  // Small intermediate move so the drag handler classifies this as a drag.
  await page.mouse.move(startX + 30, startY, { steps: 3 })
  await page.mouse.move(endX, endY, { steps: 10 })
  await page.mouse.up()
}
