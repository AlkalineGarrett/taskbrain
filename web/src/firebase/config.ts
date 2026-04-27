import { initializeApp } from 'firebase/app'
import { connectAuthEmulator, getAuth, signInAnonymously } from 'firebase/auth'
import {
  type Firestore,
  connectFirestoreEmulator,
  initializeFirestore,
  memoryLocalCache,
  persistentLocalCache,
  persistentMultipleTabManager,
} from 'firebase/firestore'

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

/**
 * Enables Firestore's persistent IndexedDB cache with multi-tab support so
 * cold-start reads come from local cache and the listener uses resume tokens
 * for delta sync. Mirrors Android's default-on persistent cache.
 *
 * The SDK auto-falls-back to memory cache if IndexedDB is unavailable; the
 * `persistentCacheError` export above signals that case to the UI.
 */
export const db: Firestore = initializeFirestore(app, {
  localCache: useEmulator
    ? memoryLocalCache()
    : persistentLocalCache({
        tabManager: persistentMultipleTabManager(),
      }),
})

if (useEmulator) {
  connectFirestoreEmulator(db, 'localhost', 8080)
  connectAuthEmulator(auth, 'http://localhost:9099', { disableWarnings: true })
  if (!auth.currentUser) {
    signInAnonymously(auth).catch((e) => {
      console.error('[firebase emulator] anonymous sign-in failed', e)
    })
  }
  console.info('[firebase emulator] wired Firestore 8080, Auth 9099')
}
