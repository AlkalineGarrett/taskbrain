import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright e2e config.
 *
 * Mirrors the Android instrumentation-test setup: the test runner does NOT
 * start the Firebase Emulator Suite — start it separately first via
 * `firebase emulators:start` (Firestore on 8080, Auth on 9099). Tests
 * self-skip / fail loudly if the emulator isn't reachable.
 *
 * The Vite dev server IS launched by Playwright with
 * `VITE_USE_FIREBASE_EMULATOR=true` so the web app wires Firestore/Auth at
 * the emulator and signs in anonymously at module init (see
 * web/src/firebase/config.ts).
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  expect: { timeout: 10_000 },
  timeout: 60_000,
  use: {
    baseURL: 'http://localhost:5174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    // Distinct port from the default 5173 so a coincident `npm run dev`
    // (no emulator flag) doesn't get reused by Playwright. `reuseExistingServer`
    // stays true so re-running tests skips the cold start.
    command: 'npm run dev -- --host 127.0.0.1 --port 5174 --strictPort',
    url: 'http://localhost:5174',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: { VITE_USE_FIREBASE_EMULATOR: 'true' },
  },
})
