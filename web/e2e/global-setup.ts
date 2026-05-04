import { requireEmulatorReachable } from './support'

/**
 * Fail fast at suite startup if the Firebase Emulator Suite isn't running.
 * Mirrors Android's `Assume.assumeTrue(BuildConfig.USE_FIREBASE_EMULATOR)`
 * — except we fail instead of skip so a forgotten emulator doesn't pass
 * silently in CI.
 */
export default async function globalSetup() {
  await requireEmulatorReachable()
}
