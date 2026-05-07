import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, unique, waitForNoteInStore } from './support'

/**
 * Pin: in the main editor, the gutter column lands at the same horizontal
 * position regardless of which row type renders it. Renders all four row
 * types in a single editor view and asserts their gutter `left` is the
 * same pixel-for-pixel:
 *
 *   1. Top-level normal line          (`EditorLine` → `selectionGutter`)
 *   2. Top-level "(N completed)"      (`CompletedPlaceholderRow` → `placeholderGutter`)
 *   3. Embedded note normal line      (`EditorLine` inside `ViewNoteSection`)
 *   4. Embedded note "(N completed)"  (`CompletedPlaceholderRow` `view` variant)
 *
 * The four gutters are produced by three different code paths
 * (EditorLine main, EditorLine embedded, CompletedPlaceholderRow main,
 * CompletedPlaceholderRow embedded view variant), each with its own
 * column-positioning math. This test fails if any one drifts.
 */
test.describe('RowAlignmentFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('all four row types share the same gutter column', async ({ page }) => {
    await gotoApp(page)

    // Embedded note: one visible line + one checked line. The visible line
    // gives us row type #3; the checked line collapses to row type #4 once
    // the parent's "Show completed" is toggled off (parent's setting
    // cascades to embedded notes via ParentShowCompletedContext).
    const embeddedTag = unique('embedded-target')
    const embeddedChecked = unique('embedded-checked')
    await seedMultiLineNote(page, `${embeddedTag}\n☑ ${embeddedChecked}`)

    // Parent note: title (#1), checked (becomes #2 placeholder), then a
    // view directive that pulls in the embedded note inline.
    const parentTitle = unique('parent-title')
    const parentChecked = unique('parent-checked')
    const parentId = await seedMultiLineNote(
      page,
      `${parentTitle}\n☑ ${parentChecked}\n[view(find(name: "${embeddedTag}"))]`,
    )
    await waitForNoteInStore(page, parentId)

    await page.getByRole('button', { name: 'All notes' }).click()
    await page.getByRole('button', { name: parentTitle }).click()
    // Wait for the embedded note to render inline before toggling.
    await expect(page.getByText(embeddedTag).first()).toBeVisible({ timeout: 10_000 })

    // Toggle "Show completed" off → both checked lines collapse to placeholders.
    await page.getByTitle('Note menu').first().click()
    await page.getByRole('button', { name: 'Show completed' }).click()
    await expect(page.getByText('(1 completed)').nth(1)).toBeVisible()

    // Collect bounding rects for the four cell types of each row, tagged
    // with whether the row lives at the top level of the editor or inside
    // an embedded view, so we can assert intra-context alignment.
    //
    // CSS module class roots used here:
    //   `selectionGutter`   / `selectionGutterHidden` — EditorLine
    //   `placeholderGutter` / `placeholderGutterView` — CompletedPlaceholderRow
    //   `noteIdCell`        / `noteIdCellView`        — both EditorLine and
    //                                                    CompletedPlaceholderRow
    //   `viewNoteIdCell`                              — ViewDirectiveRenderer
    //                                                    (the embedded line's
    //                                                    noteId column)
    const measurements = await page.evaluate(() => {
      type Row = {
        kind: 'main-line' | 'main-placeholder' | 'view-line' | 'view-placeholder'
        gutterLeft: number
        noteIdRight: number
        contentLeft: number
      }
      const rows: Row[] = []
      const rect = (el: Element | null) =>
        el ? (el as HTMLElement).getBoundingClientRect() : null
      // Where the text actually begins inside a content cell. For
      // EditorLine the inputWrapper / directiveContent is itself inside
      // an inline-padded inner wrapper, so its rect.left is post-padding.
      // For the placeholder, content holds the text directly with its
      // own paddingLeft applied; compute the post-padding X so both
      // measurements describe the same logical "text start".
      const textLeft = (el: Element) => {
        const r = (el as HTMLElement).getBoundingClientRect()
        const padLeft = parseFloat(getComputedStyle(el as HTMLElement).paddingLeft) || 0
        return r.left + padLeft
      }

      // Top-level main lines (EditorLine wrapped in `.editorRow`) — skip
      // the directive row whose own gutter is `selectionGutterHidden`.
      // Scope each lookup to direct children of `.line` so we don't reach
      // into embedded-view descendants of the directive row.
      for (const row of document.querySelectorAll('[data-line-index]')) {
        const line = row.firstElementChild
        if (!line) continue
        const gutter = Array.from(line.children).find(
          (c) => /selectionGutter/.test(c.className) && !/selectionGutterHidden/.test(c.className),
        )
        if (!gutter) continue
        const noteId = Array.from(line.children).find((c) => /noteIdCell/.test(c.className))
        // The content cell is the inline-styled inner wrapper (no class), which
        // contains either an inputWrapper or a directiveContent.
        const inner = Array.from(line.children).find(
          (c) => !/noteIdCell/.test(c.className) && !/selectionGutter/.test(c.className),
        )
        const content = inner?.querySelector('[class*="inputWrapper"], [class*="directiveContent"]')
        if (!noteId || !content) continue
        rows.push({
          kind: 'main-line',
          gutterLeft: rect(gutter)!.left,
          noteIdRight: rect(noteId)!.right,
          contentLeft: textLeft(content),
        })
      }

      // Top-level placeholders (`.placeholder`, not `.placeholderView`).
      for (const row of document.querySelectorAll(
        '[class*="placeholder"]:not([class*="placeholderView"]):not([class*="placeholderGutter"]):not([class*="placeholderContent"])',
      )) {
        const gutter = row.querySelector('[class*="placeholderGutter"]')
        const noteId = row.querySelector('[class*="noteIdCell"]')
        const content = row.querySelector(`[class*="content"]:not([class*="placeholderContent"])`)
        if (!gutter || !noteId || !content) continue
        rows.push({
          kind: 'main-placeholder',
          gutterLeft: rect(gutter)!.left,
          noteIdRight: rect(noteId)!.right,
          contentLeft: textLeft(content),
        })
      }

      // Embedded view lines (`viewLineRow`).
      for (const row of document.querySelectorAll('[data-view-line-index]')) {
        const gutter = row.querySelector(
          '[class*="selectionGutter"]:not([class*="selectionGutterHidden"])',
        )
        const noteId = row.querySelector('[class*="viewNoteIdCell"]')
        const content = row.querySelector('[class*="inputWrapper"], [class*="directiveContent"]')
        if (!gutter || !noteId || !content) continue
        rows.push({
          kind: 'view-line',
          gutterLeft: rect(gutter)!.left,
          noteIdRight: rect(noteId)!.right,
          contentLeft: textLeft(content),
        })
      }

      // Embedded view placeholders (`.placeholderView`).
      for (const row of document.querySelectorAll('[class*="placeholderView"]')) {
        const gutter = row.querySelector('[class*="placeholderGutter"]')
        const noteId = row.querySelector('[class*="noteIdCell"]')
        const content = row.querySelector(`[class*="content"]:not([class*="placeholderContent"])`)
        if (!gutter || !noteId || !content) continue
        rows.push({
          kind: 'view-placeholder',
          gutterLeft: rect(gutter)!.left,
          noteIdRight: rect(noteId)!.right,
          contentLeft: textLeft(content),
        })
      }
      return rows
    })

    // One row of each kind is expected.
    expect(measurements.map((m) => m.kind).sort(), JSON.stringify(measurements, null, 2))
      .toEqual(['main-line', 'main-placeholder', 'view-line', 'view-placeholder'])

    // ── 1. Gutters: all four rows must align horizontally. ─────────────
    const gutterRef = measurements[0].gutterLeft
    for (const m of measurements) {
      expect(
        Math.abs(m.gutterLeft - gutterRef),
        `${m.kind} gutter drifted ${m.gutterLeft - gutterRef}px (all=${JSON.stringify(measurements)})`,
      ).toBeLessThan(1)
    }

    // ── 2. NoteId right-edges: align within each context. ──────────────
    // (Top-level vs embedded differ by `--view-border-inset` because the
    // embedded view sits inside `viewContainer`'s 7px border+padding;
    // that's a designed offset, not a bug. We only assert intra-context.)
    const mainNoteIdRight = measurements.find((m) => m.kind === 'main-line')!.noteIdRight
    const mainPhNoteIdRight = measurements.find((m) => m.kind === 'main-placeholder')!.noteIdRight
    expect(
      Math.abs(mainPhNoteIdRight - mainNoteIdRight),
      `main-placeholder noteId drifted ${mainPhNoteIdRight - mainNoteIdRight}px from main-line noteId`,
    ).toBeLessThan(1)

    const viewNoteIdRight = measurements.find((m) => m.kind === 'view-line')!.noteIdRight
    const viewPhNoteIdRight = measurements.find((m) => m.kind === 'view-placeholder')!.noteIdRight
    expect(
      Math.abs(viewPhNoteIdRight - viewNoteIdRight),
      `view-placeholder noteId drifted ${viewPhNoteIdRight - viewNoteIdRight}px from view-line noteId`,
    ).toBeLessThan(1)

    // ── 3. Content left-edges: align within each context. ──────────────
    const mainContent = measurements.find((m) => m.kind === 'main-line')!.contentLeft
    const mainPhContent = measurements.find((m) => m.kind === 'main-placeholder')!.contentLeft
    expect(
      Math.abs(mainPhContent - mainContent),
      `main-placeholder content drifted ${mainPhContent - mainContent}px from main-line content`,
    ).toBeLessThan(1)

    const viewContent = measurements.find((m) => m.kind === 'view-line')!.contentLeft
    const viewPhContent = measurements.find((m) => m.kind === 'view-placeholder')!.contentLeft
    expect(
      Math.abs(viewPhContent - viewContent),
      `view-placeholder content drifted ${viewPhContent - viewContent}px from view-line content`,
    ).toBeLessThan(1)
  })
})
