import { Outlet } from 'react-router-dom'
import { NavBar } from './NavBar'
import { LifecycleStatusBanner } from './LifecycleStatusBanner'
import styles from './AppLayout.module.css'

export function AppLayout() {
  return (
    <div className={styles.layout}>
      <LifecycleStatusBanner />
      <NavBar />
      <Outlet />
    </div>
  )
}
