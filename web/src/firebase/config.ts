import { initializeApp } from 'firebase/app'
import { connectAuthEmulator, getAuth, signInAnonymously } from 'firebase/auth'
import type { Firestore } from 'firebase/firestore'

import { firestoreLifecycle } from './lifecycle'

const firebaseConfig = {
  apiKey: "AIzaSyDbbjG7ynlks5DodHoATVjJLHu_K_JX3KI",
  authDomain: "adhd-prompter.firebaseapp.com",
  projectId: "adhd-prompter",
  storageBucket: "adhd-prompter.firebasestorage.app",
  messagingSenderId: "613948682660",
  appId: "1:613948682660:web:placeholder",
}

const app = initializeApp(firebaseConfig)
export const auth = getAuth(app)

const useEmulator = import.meta.env.VITE_USE_FIREBASE_EMULATOR === 'true'

/**
 * Diagnostic: best-effort feature detection of IndexedDB. If unavailable
 * (private browsing on some Safari versions, very old browsers, blocked
 * by extensions), the Firestore SDK silently falls back to memory-only
 * caching — meaning every cold start re-fetches the full collection.
 * Captured at module init so consumers can surface a banner.
 */
export let persistentCacheError: string | null = null

function detectIndexedDbAvailability(): string | null {
  if (typeof window === 'undefined') return null
  if (typeof window.indexedDB === 'undefined') {
    return 'IndexedDB is not available in this browser; Firestore persistence falls back to memory cache (every cold start re-fetches all notes).'
  }
  return null
}
persistentCacheError = detectIndexedDbAvailability()
if (persistentCacheError) {
  console.warn(`[Firestore persistence] ${persistentCacheError}`)
}

firestoreLifecycle.configure(app, auth, useEmulator)

if (useEmulator) {
  connectAuthEmulator(auth, 'http://localhost:9099', { disableWarnings: true })
  if (!auth.currentUser) {
    signInAnonymously(auth).catch((e) => {
      console.error('[firebase emulator] anonymous sign-in failed', e)
    })
  }
  console.info('[firebase emulator] wired Auth 9099 (Firestore wired by lifecycle on start)')
}

/**
 * Returns the live Firestore instance. Throws if the lifecycle is currently
 * stopped — callers in user-driven code paths should never see this because
 * the visibility observer guarantees lifecycle.start before the tab is
 * interactive. Pure background code (e.g. imports during module load) MUST
 * NOT call this — it is only safe inside hook bodies, event handlers, and
 * effect callbacks that fire on visible tabs.
 */
export function getDb(): Firestore {
  return firestoreLifecycle.requireDb()
}
