import { test, expect } from '@playwright/test'
import { clearFirestore, gotoApp } from './support'

/**
 * Mirrors `SignOutFlowTest.kt`.
 *
 * Click "Sign out" in the NavBar → ProtectedRoute drops to <LoginScreen />
 * → assert the "Sign in with Google" button is visible.
 *
 * Note: in emulator mode the app signs in anonymously at module init, but
 * once signed out within this page session it stays out (config.ts only
 * runs the anon sign-in once, gated on `!auth.currentUser`). Each
 * Playwright test gets a fresh page, so the next test re-runs anon
 * sign-in cleanly.
 */
test.describe('SignOutFlow', () => {
  test.beforeEach(async () => { await clearFirestore() })

  test('Sign out returns to LoginScreen', async ({ page }) => {
    await gotoApp(page)

    await page.getByRole('button', { name: 'Sign out' }).click()

    // LoginScreen renders APP_NAME, LOGIN_SUBTITLE, and the Google button.
    await expect(page.getByRole('heading', { name: 'TaskBrain' })).toBeVisible()
    await expect(page.getByText('ADHD-friendly task management')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Sign in with Google' })).toBeVisible()
  })
})
