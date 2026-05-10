import { firestoreLifecycle } from './lifecycle'

/**
 * Drives FirestoreLifecycle.start()/stop() based on tab visibility + an
 * inactivity timer. The point is to fully terminate the Firestore SDK on
 * inactive tabs so they stop contesting the IndexedDB multi-tab lease;
 * lease churn between two open tabs was triggering full collection
 * re-snapshots on every WebChannel cycle, costing ~1599 reads per transfer.
 *
 * Behavior:
 * - Tab becomes hidden → start an idle countdown.
 * - Countdown elapses → lifecycle.stop() (terminates Firestore SDK).
 * - Tab becomes visible OR user input while visible → cancel countdown,
 *   ensure lifecycle is started.
 *
 * The 5-minute threshold is short enough that an inactive tab pays no
 * background read cost across an idle hour, and long enough that quick
 * alt-tabs don't pay the wake-up latency.
 */

const IDLE_TIMEOUT_MS = 5 * 60 * 1000

let idleTimer: ReturnType<typeof setTimeout> | null = null
let installedTeardown: (() => void) | null = null

function clearIdleTimer(): void {
  if (idleTimer != null) {
    clearTimeout(idleTimer)
    idleTimer = null
  }
}

function scheduleIdleStop(): void {
  clearIdleTimer()
  idleTimer = setTimeout(() => {
    idleTimer = null
    void firestoreLifecycle.stop().catch((e) => {
      console.error('[idleLifecycleObserver] lifecycle.stop failed', e)
    })
  }, IDLE_TIMEOUT_MS)
}

function ensureStarted(): void {
  void firestoreLifecycle.start().catch((e) => {
    console.error('[idleLifecycleObserver] lifecycle.start failed', e)
  })
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') {
    clearIdleTimer()
    ensureStarted()
  } else {
    scheduleIdleStop()
  }
}

function onUserActivity(): void {
  if (document.visibilityState !== 'visible') return
  // Skip the Promise-chain allocation on every keystroke when start is a no-op.
  const state = firestoreLifecycle.state
  if (state === 'stopped' || state === 'stopping') ensureStarted()
}

/**
 * Idempotent. Installs the visibility + activity listeners and starts the
 * lifecycle if the tab is currently visible. Calling again teardowns the
 * prior install first so Vite HMR re-evaluations don't accumulate listeners.
 */
export function startIdleLifecycleObserver(): void {
  if (typeof document === 'undefined') return
  installedTeardown?.()

  document.addEventListener('visibilitychange', onVisibilityChange)
  // Capture-phase so input is observed even if a child stops propagation.
  // The early-return in onUserActivity keeps the hot path cheap; start() is
  // idempotent so the call after the visibility check is a no-op when
  // already started.
  window.addEventListener('mousedown', onUserActivity, { capture: true, passive: true })
  window.addEventListener('keydown', onUserActivity, { capture: true, passive: true })
  window.addEventListener('focus', onUserActivity, { capture: true })

  installedTeardown = () => {
    clearIdleTimer()
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('mousedown', onUserActivity, { capture: true } as EventListenerOptions)
    window.removeEventListener('keydown', onUserActivity, { capture: true } as EventListenerOptions)
    window.removeEventListener('focus', onUserActivity, { capture: true } as EventListenerOptions)
  }

  if (import.meta.hot) {
    import.meta.hot.dispose(() => installedTeardown?.())
  }

  if (document.visibilityState === 'visible') {
    ensureStarted()
  } else {
    scheduleIdleStop()
  }
}
