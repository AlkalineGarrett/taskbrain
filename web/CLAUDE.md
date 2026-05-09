# Web app

TypeScript + React (Vite). Source under `web/src/`, tests under `web/src/__tests__/`, end-to-end Playwright specs under `web/e2e/`.

## Build commands

Run from the repo root (or `cd web` and drop the `--prefix web`):

```bash
npm --prefix web run dev          # Vite dev server
npm --prefix web run build        # tsc -b && vite build (typecheck + production bundle)
npm --prefix web run preview      # Serve the built bundle
npm --prefix web run test         # Vitest unit tests (one shot)
npm --prefix web run test:watch   # Vitest in watch mode
npm --prefix web run test:e2e     # Playwright e2e
npm --prefix web run test:e2e:ui  # Playwright with the UI runner
```

Type-check only (no bundle):
```bash
cd web && npx tsc --noEmit
```

Single test file:
```bash
cd web && npx vitest run src/__tests__/data/NoteRepository.test.ts
```

## Running against the Firebase emulator

Bring up the shared environment first (see top-level `CLAUDE.md` — use `scripts/test-env-up.sh`; the AVD isn't needed for web). Then:

```bash
npm --prefix web run dev
```

The dev server picks up `web/.env.development` (committed) which sets `VITE_USE_FIREBASE_EMULATOR=true` — Firestore + Auth get pointed at `localhost:8080` / `localhost:9099` automatically. Production builds (`npm --prefix web run build`) run in `production` mode and ignore `.env.development`, so they correctly hit the real project.

To override locally — e.g., point `npm run dev` at the prod project for one-off verification — create `web/.env.development.local` with `VITE_USE_FIREBASE_EMULATOR=false`. That file is gitignored.

The flag is read in `web/src/firebase/config.ts` as `import.meta.env.VITE_USE_FIREBASE_EMULATOR === 'true'`.

## Testing stack

- **Vitest** for unit tests (`web/src/__tests__/`). Run with `npm --prefix web run test`.
- **Playwright** for end-to-end (`web/e2e/`). Note: Vitest tries to scan `e2e/` and prints "FAIL Playwright Test did not expect test.describe()" — those aren't real failures, just incompatible runners. Use `npm --prefix web run test:e2e` to actually run them.
