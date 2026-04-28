import { useRef, useState, type ReactNode } from 'react'
import { useClickOutside } from '@/hooks/useClickOutside'
import styles from './DropdownMenu.module.css'

export function useDropdown() {
  const ref = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  const toggle = () => setOpen((prev) => !prev)
  useClickOutside(ref, open, close)
  return { ref, open, close, toggle }
}

export function DropdownMenuContainer({
  innerRef,
  children,
}: {
  innerRef: React.RefObject<HTMLDivElement | null>
  children: ReactNode
}) {
  return <div className={styles.container} ref={innerRef}>{children}</div>
}

export function DropdownMenuPanel({ children }: { children: ReactNode }) {
  return <div className={styles.menu}>{children}</div>
}

export function MenuItem({
  icon,
  label,
  onClick,
  danger = false,
}: {
  icon?: ReactNode
  label: string
  onClick: () => void
  danger?: boolean
}) {
  return (
    <button
      className={`${styles.item} ${danger ? styles.itemDanger : ''}`}
      onClick={onClick}
    >
      {icon != null && <span className={styles.itemIcon}>{icon}</span>}
      {label}
    </button>
  )
}
