import { useSyncExternalStore } from 'react'
import { firestoreLifecycle, type LifecycleSnapshot } from './lifecycle'

/**
 * Re-renders on every FirestoreLifecycle state transition. The returned
 * snapshot is reference-stable within a state, so it's safe to use in
 * useMemo / useEffect dependency arrays. `generation` increments on every
 * start so consumers that capture db-derived values (collection refs, repo
 * instances) can rebuild after a wake-from-idle.
 */
export function useFirestoreLifecycleSnapshot(): LifecycleSnapshot {
  return useSyncExternalStore(
    firestoreLifecycle.subscribeState.bind(firestoreLifecycle),
    firestoreLifecycle.getSnapshot,
    firestoreLifecycle.getSnapshot,
  )
}
