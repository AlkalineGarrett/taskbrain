import { useFirestoreLifecycleSnapshot } from '@/firebase/useFirestoreLifecycle'
import { REFRESHING } from '@/strings'
import styles from './LifecycleStatusBanner.module.css'

/**
 * Surfaces a "Refreshing…" indicator while the FirestoreLifecycle is in a
 * non-started state. The lifecycle stops on idle (5 min hidden) and starts
 * on tab wake; the gap between visibilitychange and the first listener
 * snapshot can take a few hundred ms during which subscribers see empty
 * data. The banner makes that transition explicit instead of mysterious.
 */
export function LifecycleStatusBanner() {
  const { state } = useFirestoreLifecycleSnapshot()
  if (state === 'started') return null
  return <div className={styles.banner}>{REFRESHING}</div>
}
