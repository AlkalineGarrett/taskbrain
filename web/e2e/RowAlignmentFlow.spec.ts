import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp, seedMultiLineNote, unique, waitForNoteInStore } from './support'

/**
 * Pin: every row in the editor — main lines, main placeholders, embedded
 * view lines, embedded view placeholders — places its noteId, gutter,
 * and content into the SAME column tracks of the editor's CSS Grid.
 * After the flat-tree refactor that turned the embedded view from a
 * negative-margin escape into flat siblings of the directive line in
 * the editor grid, alignment is no longer "within each context" but
 * "across all rows uniformly". The test fails if any of the three
 * positioning paths (EditorLine subgrid placement, CompletedPlaceholder
 * subgrid placement, ViewNoteSection's subgrid wrapper) drifts.
 */
test.describe('RowAlignmentFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('all four row types share the editor grid column tracks', async ({ page }) => {
    await gotoApp(page)

    // Embedded note: one visible line + one checked line. The visible line
    // gives us a view-line; the checked line collapses to a view-
    // placeholder once the parent's "Show completed" is off (the parent's
    // setting cascades to embedded notes via ParentShowCompletedContext).
    const embeddedTag = unique('embedded-target')
    const embeddedChecked = unique('embedded-checked')
    await seedMultiLineNote(page, `${embeddedTag}\n☑ ${embeddedChecked}`)

    // Parent note: title (main-line), checked (becomes main-placeholder),
    // then a view directive that pulls in the embedded note inline.
    const parentTitle = unique('parent-title')
    const parentChecked = unique('parent-checked')
    const parentId = await seedMultiLineNote(
      page,
      `${parentTitle}\n☑ ${parentChecked}\n[view(find(name: "${embeddedTag}"))]`,
    )
    await waitForNoteInStore(page, parentId)

    await page.getByRole('button', { name: 'All notes' }).click()
    await page.getByRole('button', { name: parentTitle }).click()
    await expect(page.getByText(embeddedTag).first()).toBeVisible({ timeout: 10_000 })

    // Toggle "Show completed" off → both checked lines collapse to placeholders.
    await page.getByTitle('Note menu').first().click()
    await page.getByRole('button', { name: 'Show completed' }).click()
    await expect(page.getByText('(1 completed)').nth(1)).toBeVisible()

    // Locate the four rows and measure where their gutter, noteId, and
    // content cells land. CSS module classes used:
    //   `selectionGutter`   — EditorLine's gutter cell
    //   `placeholderGutter` — CompletedPlaceholderRow's gutter cell
    //   `noteIdCell`        — both EditorLine + CompletedPlaceholderRow
    //   `placeholder`       — CompletedPlaceholderRow's outer row
    //                         (excluded sub-classes: `placeholderGutter`,
    //                         `placeholderContent` legacy, etc.)
    //   `[data-view-note-id]` ancestor — embedded vs. top-level
    const measurements = await page.evaluate(() => {
      type Kind = 'main-line' | 'main-placeholder' | 'view-line' | 'view-placeholder'
      type Row = { kind: Kind; gutterLeft: number; noteIdRight: number; contentLeft: number }
      const rows: Row[] = []

      const rect = (el: Element) => (el as HTMLElement).getBoundingClientRect()
      // Where text actually starts inside a content cell, accounting for
      // the cell's own paddingLeft (matters for the placeholder, whose
      // text is a direct child rather than a nested wrapper).
      const textLeft = (el: Element) => {
        const r = rect(el)
        const padLeft = parseFloat(getComputedStyle(el as HTMLElement).paddingLeft) || 0
        return r.left + padLeft
      }
      // The noteSection wrapper is unique to ViewDirectiveRenderer's
      // per-note container; any row inside one belongs to an embedded view.
      const isInsideEmbeddedView = (el: Element) =>
        el.closest('[class*="noteSection"]') != null

      // Main-editor lines and embedded view lines — both have an EditorLine
      // `.line` whose direct children are noteIdCell, selectionGutter, and
      // an inline-styled inner wrapper containing the content.
      for (const wrapper of document.querySelectorAll('[data-line-index], [data-view-line-index]')) {
        const line = wrapper.firstElementChild
        if (!line) continue
        const gutter = Array.from(line.children).find(
          (c) => /selectionGutter/.test(c.className),
        )
        const noteId = Array.from(line.children).find((c) => /noteIdCell/.test(c.className))
        // The inner wrapper is the only child without one of those classes.
        const inner = Array.from(line.children).find(
          (c) => !/noteIdCell/.test(c.className) && !/selectionGutter/.test(c.className),
        )
        const content = inner?.querySelector('[class*="inputWrapper"], [class*="directiveContent"]')
        if (!gutter || !noteId || !content) continue
        const embedded = isInsideEmbeddedView(wrapper)
        rows.push({
          kind: embedded ? 'view-line' : 'main-line',
          gutterLeft: rect(gutter).left,
          noteIdRight: rect(noteId).right,
          contentLeft: textLeft(content),
        })
      }

      // Placeholders. `.placeholder` matches every CompletedPlaceholderRow,
      // including its descendant cells (`.placeholderGutter` etc.) — narrow
      // to the outer row by also requiring `[class*="grid"]` is too brittle;
      // instead find children with the gutter class and bubble up.
      for (const ph of document.querySelectorAll('[class*="placeholderGutter"]')) {
        const row = ph.parentElement
        if (!row) continue
        const noteId = row.querySelector(':scope > [class*="noteIdCell"]')
        const content = row.querySelector(':scope > [class*="content"]')
        if (!noteId || !content) continue
        const embedded = isInsideEmbeddedView(row)
        rows.push({
          kind: embedded ? 'view-placeholder' : 'main-placeholder',
          gutterLeft: rect(ph).left,
          noteIdRight: rect(noteId).right,
          contentLeft: textLeft(content),
        })
      }

      return rows
    })

    // The seeded note produces the directive line as a second main-line
    // (its selectionGutter is just a normal gutter now — there's no
    // longer a "hide the gutter when an embedded view shadows it" trick).
    // Expect all four kinds present, and at least one of each.
    const kinds = new Set(measurements.map((m) => m.kind))
    for (const k of ['main-line', 'main-placeholder', 'view-line', 'view-placeholder'] as const) {
      expect(kinds.has(k), `missing ${k} (got ${JSON.stringify(measurements, null, 2)})`).toBe(true)
    }

    // After the flat-tree refactor, all rows are direct grid items
    // (or subgrid descendants) of the editor's column template:
    // gutters and noteIds align across all four kinds. Content
    // alignment differs by `--embedded-content-inset` (the gap that
    // makes room for the embedded view's vertical bar inside the
    // [content] column), so we only assert intra-context content
    // alignment — top-level pair vs. embedded pair.
    const checkAlignedAll = (key: 'gutterLeft' | 'noteIdRight', label: string) => {
      const ref = measurements[0][key]
      for (const m of measurements) {
        expect(
          Math.abs(m[key] - ref),
          `${m.kind} ${label} drifted ${m[key] - ref}px (all=${JSON.stringify(measurements)})`,
        ).toBeLessThan(1)
      }
    }
    checkAlignedAll('gutterLeft', 'gutter')
    checkAlignedAll('noteIdRight', 'noteId right edge')

    const checkContentPair = (a: typeof measurements[number]['kind'], b: typeof measurements[number]['kind']) => {
      const x = measurements.find((m) => m.kind === a)!.contentLeft
      const y = measurements.find((m) => m.kind === b)!.contentLeft
      expect(
        Math.abs(x - y),
        `${a} content drifted ${y - x}px from ${b} content (all=${JSON.stringify(measurements)})`,
      ).toBeLessThan(1)
    }
    checkContentPair('main-line', 'main-placeholder')
    checkContentPair('view-line', 'view-placeholder')
  })
})
