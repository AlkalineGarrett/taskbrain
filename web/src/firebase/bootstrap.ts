import type { Auth } from 'firebase/auth'
import type { Firestore } from 'firebase/firestore'
import { noteStore } from '@/data/NoteStore'
import { noteStatsRepo } from '@/data/NoteStatsRepository'
import { recentTabsRepo } from '@/data/RecentTabsRepository'
import { firestoreLifecycle } from './lifecycle'
import { startIdleLifecycleObserver } from './idleLifecycleObserver'

interface LifecycleBoundRepo {
  attach(db: Firestore, auth: Auth): void
  detach(): void
}

let installed = false

/**
 * One-shot wiring: registers the singleton repos with the lifecycle so they
 * auto-attach on every start and auto-detach on every stop. Also installs
 * the idle/visibility observer that drives lifecycle.start()/stop(). Called
 * once at app boot.
 */
export function installFirestoreBootstrap(): void {
  if (installed) return
  installed = true

  for (const repo of [noteStore, noteStatsRepo, recentTabsRepo] as LifecycleBoundRepo[]) {
    firestoreLifecycle.subscribe(
      ({ db, auth }) => repo.attach(db, auth),
      () => repo.detach(),
    )
  }

  startIdleLifecycleObserver()
}
