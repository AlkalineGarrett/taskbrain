import { MdIcon } from './MdIcon'
import styles from './ActionButton.module.css'

export function ActionButton({
  icon,
  label,
  onClick,
}: {
  icon: string
  label: string
  onClick: () => void
}) {
  return (
    <button className={styles.actionButton} onClick={onClick}>
      <MdIcon path={icon} className={styles.actionButtonIcon} />
      {label}
    </button>
  )
}
