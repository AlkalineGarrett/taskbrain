import { expect, type Page } from '@playwright/test'

/**
 * Shared helpers for the Playwright suite.
 *
 * Mirrors EmulatorTestSupport.kt for Android: provides anon-auth gating,
 * seeding via the real NoteRepository (not test fixtures), and selector
 * shortcuts equivalent to `waitAndClickText` / `waitAndClickContentDescription`.
 *
 * Seeding strategy: dynamic-import the web modules from the page context
 * (Vite serves them at `/src/...`). This runs the SAME `createMultiLineNote`
 * the app uses, so the test exercises the production code path — exactly
 * how the Android tests call `NoteRepository.createMultiLineNote()`
 * directly against the emulator.
 */

/** Firestore emulator host:port. Must match firebase.json + firebase/config.ts. */
const EMULATOR_FIRESTORE = 'http://localhost:8080'
const EMULATOR_AUTH = 'http://localhost:9099'

/** Random suffix so seeded content can't collide across tests. */
export const unique = (label: string) =>
  `${label}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`

/**
 * Confirm the Firebase Emulator Suite is reachable. Called from globalSetup.
 * Throws a loud, useful message if not — analogous to Android's
 * `Assume.assumeTrue(BuildConfig.USE_FIREBASE_EMULATOR)`, except we fail
 * rather than skip because Playwright doesn't have a runtime skip primitive
 * at the suite level and silent skips are easy to miss in CI.
 */
export async function requireEmulatorReachable() {
  const errors: string[] = []
  for (const [name, url] of [
    ['firestore', `${EMULATOR_FIRESTORE}/`],
    ['auth', `${EMULATOR_AUTH}/`],
  ] as const) {
    try {
      const res = await fetch(url)
      if (!res.ok && res.status !== 404) {
        errors.push(`${name} at ${url} returned ${res.status}`)
      }
    } catch (e) {
      errors.push(`${name} at ${url} unreachable: ${(e as Error).message}`)
    }
  }
  if (errors.length > 0) {
    throw new Error(
      `Firebase emulator not reachable. Start it first with ` +
        `\`firebase emulators:start\` from the repo root.\n` +
        errors.map((e) => `  - ${e}`).join('\n'),
    )
  }
}

/**
 * Wipes all Firestore data in the project. Called between tests so each
 * test starts with a clean slate — equivalent to Android's `NoteStore.clear()`
 * + per-test fresh state, except it also clears server-side state.
 */
export async function clearFirestore() {
  const res = await fetch(
    `${EMULATOR_FIRESTORE}/emulator/v1/projects/adhd-prompter/databases/(default)/documents`,
    { method: 'DELETE' },
  )
  if (!res.ok) {
    throw new Error(`clearFirestore failed: ${res.status} ${await res.text()}`)
  }
}

/**
 * Navigate to the app and wait for the auth gate to clear. The web app
 * signs in anonymously at module init when VITE_USE_FIREBASE_EMULATOR=true,
 * so we just wait for the post-auth nav bar to appear. Mirrors Android's
 * "ensure anonymous user exists before MainActivity launches".
 */
export async function gotoApp(page: Page, path: string = '/') {
  await page.goto(path)
  // NavBar's "All notes" button is in every signed-in screen.
  await expect(page.getByRole('button', { name: 'All notes' })).toBeVisible({
    timeout: 15_000,
  })
}

/**
 * Seed a multi-line note by invoking the real `NoteRepository.createMultiLineNote`
 * from the page context. Returns the new note's id.
 *
 * Requires `gotoApp` to have run first so Firebase is initialized and the
 * anonymous user is signed in. Equivalent to Android's
 * `EmulatorTestSupport.seedMultiLineNote`.
 *
 * After the write, restart the noteStore so its next listener emit is an
 * initial snapshot — which calls `rebuildAll()` and populates the public
 * `reconstructedNotes` array. Without this, the listener treats the seed
 * as our-own-echo and skips the rebuild (an intentional optimization for
 * editor save round-trips that would otherwise clobber typed text), and
 * the list UI shows "No notes found" even though `rawNotes` contains the
 * note. Production code doesn't hit this because the editor session is
 * the source of truth during save; only out-of-band seeds expose the gap.
 */
export async function seedMultiLineNote(page: Page, content: string): Promise<string> {
  return page.evaluate(async (c: string) => {
    const cfg = await import('/src/firebase/config.ts')
    const repoMod = await import('/src/data/NoteRepository.ts')
    const storeMod = await import('/src/data/NoteStore.ts')
    if (!cfg.auth.currentUser) {
      await new Promise<void>((resolve) => {
        const unsub = cfg.auth.onAuthStateChanged((u) => {
          if (u) {
            unsub()
            resolve()
          }
        })
      })
    }
    const repo = new repoMod.NoteRepository(cfg.db, cfg.auth)
    const id = await repo.createMultiLineNote(c)
    storeMod.noteStore.clear()
    storeMod.noteStore.start(cfg.db, cfg.auth)
    await storeMod.noteStore.ensureLoaded()
    return id
  }, content)
}

/**
 * Wait for `noteStore` to have received the note via its snapshot listener
 * AND for the reconstructed top-level notes array (what the UI's
 * `useSyncExternalStore` subscribes to via `getSnapshot()`) to include it.
 * Necessary because `createMultiLineNote().commit()` resolves before the
 * snapshot lands and before reconstructedNotes is rebuilt — without the
 * second check the UI may still render "No notes found". Equivalent to
 * Android's `waitForNoteInStore` (which only checks raw, since Compose's
 * StateFlow already gates re-renders on the same publish).
 */
export async function waitForNoteInStore(page: Page, noteId: string, timeoutMs = 10_000) {
  await page.waitForFunction(
    async (id: string) => {
      const mod = await import('/src/data/NoteStore.ts')
      const inRaw = mod.noteStore.getRawNoteById(id) != null
      const inReconstructed = mod.noteStore.getSnapshot().some((n) => n.id === id)
      // A non-root child note will be in raw but not reconstructed; the
      // tests only seed root notes, so require both.
      return inRaw && inReconstructed
    },
    noteId,
    { timeout: timeoutMs },
  )
}

/**
 * Wait for the editor's save status to be "Saved" (the SAVED string from
 * strings.ts). Equivalent to Android's
 * `waitUntilAtLeastOneExists(hasText(savedLabel))`.
 */
export async function waitForSaved(page: Page, timeoutMs = 10_000) {
  await expect(page.getByRole('button', { name: 'Saved' })).toBeVisible({ timeout: timeoutMs })
}

/**
 * Force the noteStore to re-sync from Firestore (clear + start +
 * ensureLoaded). Use after save flows that create or modify notes — the
 * listener's echo-suppression skips rebuilding `reconstructedNotes` for
 * our own writes, leaving the list UI stale. A fresh start triggers an
 * initial snapshot which calls `rebuildAll()`. Production users rarely
 * hit this because the editor's optimistic `updateNoteSilently` keeps
 * reconstructedNotes current during typing; brand-new notes from
 * `createNote()` (no optimistic insert) and out-of-band test seeds do
 * surface the gap.
 */
export async function syncNoteStore(page: Page) {
  await page.evaluate(async () => {
    const cfg = await import('/src/firebase/config.ts')
    const storeMod = await import('/src/data/NoteStore.ts')
    storeMod.noteStore.clear()
    storeMod.noteStore.start(cfg.db, cfg.auth)
    await storeMod.noteStore.ensureLoaded()
  })
}

/**
 * Wait for noteStore.reconstructedNotes to contain a top-level note whose
 * content includes `substr`. After save, calls `syncNoteStore` first so
 * the listener's own-echo suppression doesn't leave the list stale.
 */
export async function waitForNoteInStoreContaining(
  page: Page,
  substr: string,
  timeoutMs = 15_000,
) {
  await syncNoteStore(page)
  await page.waitForFunction(
    async (s: string) => {
      const mod = await import('/src/data/NoteStore.ts')
      return mod.noteStore.getSnapshot().some((n) => n.content.includes(s))
    },
    substr,
    { timeout: timeoutMs },
  )
}
