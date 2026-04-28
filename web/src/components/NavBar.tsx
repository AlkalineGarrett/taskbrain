import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { recentTabsRepo } from '@/data/RecentTabsRepository'
import { APP_NAME, NAV_CURRENT_NOTE, NAV_ALL_NOTES, NAV_ALARMS, NAV_ADMIN, SIGN_OUT } from '@/strings'
import styles from './NavBar.module.css'

export function NavBar() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, signOut } = useAuth()

  const isNoteEditor = location.pathname.startsWith('/note/')
  const isNoteList = location.pathname === '/'
  const isAdmin = location.pathname === '/admin'

  return (
    <nav className={styles.navbar}>
      <h1 className={styles.appTitle}>{APP_NAME}</h1>

      <div className={styles.navItems}>
        <button
          className={`${styles.navItem} ${isNoteEditor ? styles.active : ''}`}
          onClick={async () => {
            if (isNoteEditor) return
            // Prefer this device's last-viewed note. On a fresh device the
            // shared history is the fallback — pick its most-recent entry.
            const lastNoteId = localStorage.getItem('lastNoteId')
            if (lastNoteId) {
              navigate(`/note/${lastNoteId}`)
              return
            }
            const tabs = await recentTabsRepo.getOpenTabs()
            if (tabs[0]) navigate(`/note/${tabs[0].noteId}`)
          }}
        >
          {NAV_CURRENT_NOTE}
        </button>
        <button
          className={`${styles.navItem} ${isNoteList ? styles.active : ''}`}
          onClick={() => navigate('/')}
        >
          {NAV_ALL_NOTES}
        </button>
        <button
          className={styles.navItem}
          disabled
        >
          {NAV_ALARMS}
        </button>
        <button
          className={`${styles.navItem} ${isAdmin ? styles.active : ''}`}
          onClick={() => navigate('/admin')}
        >
          {NAV_ADMIN}
        </button>
      </div>

      <div className={styles.rightSection}>
        <span className={styles.userName}>{user?.displayName}</span>
        <button className={styles.signOutButton} onClick={signOut}>
          {SIGN_OUT}
        </button>
      </div>
    </nav>
  )
}
