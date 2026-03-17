import { useState, useRef, useEffect } from 'react'
import type { EditorController } from '@/editor/EditorController'
import {
  SAVE, SAVING, SAVED,
  COMMAND_TOGGLE_BULLET, COMMAND_TOGGLE_CHECKBOX,
  COMMAND_INDENT, COMMAND_UNINDENT,
  COMMAND_MOVE_UP, COMMAND_MOVE_DOWN,
  DELETE_NOTE, RESTORE_NOTE, NOTE_MENU,
  SHOW_COMPLETED,
} from '@/strings'
import styles from './CommandBar.module.css'

interface CommandBarProps {
  controller: EditorController
  onSave: () => void
  onUndo?: () => void
  onRedo?: () => void
  onDelete?: () => void
  onRestore?: () => void
  isDeleted?: boolean
  dirty: boolean
  saving: boolean
  showCompleted: boolean
  onToggleShowCompleted: () => void
}

export function CommandBar({
  controller, onSave, onUndo, onRedo, onDelete, onRestore, isDeleted, dirty, saving,
  showCompleted, onToggleShowCompleted,
}: CommandBarProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menuOpen) return
    function handleClickOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [menuOpen])

  return (
    <div className={styles.bar}>
      <div className={styles.group}>
        <button
          className={styles.button}
          onClick={() => controller.toggleBullet()}
          title={COMMAND_TOGGLE_BULLET}
        >
          •
        </button>
        <button
          className={styles.button}
          onClick={() => controller.toggleCheckbox()}
          title={COMMAND_TOGGLE_CHECKBOX}
        >
          ☐
        </button>
      </div>

      <div className={styles.group}>
        <button
          className={styles.button}
          onClick={() => controller.indent()}
          title={COMMAND_INDENT}
        >
          →
        </button>
        <button
          className={styles.button}
          onClick={() => controller.unindent()}
          title={COMMAND_UNINDENT}
        >
          ←
        </button>
      </div>

      <div className={styles.group}>
        <button
          className={`${styles.button} ${controller.getMoveUpState().isWarning ? styles.warning : ''}`}
          onClick={() => controller.moveUp()}
          title={COMMAND_MOVE_UP}
          disabled={!controller.getMoveUpState().isEnabled}
        >
          ↑
        </button>
        <button
          className={`${styles.button} ${controller.getMoveDownState().isWarning ? styles.warning : ''}`}
          onClick={() => controller.moveDown()}
          title={COMMAND_MOVE_DOWN}
          disabled={!controller.getMoveDownState().isEnabled}
        >
          ↓
        </button>
      </div>

      <div className={styles.group}>
        <button
          className={styles.button}
          onClick={onUndo ?? (() => controller.undo())}
          title="Undo (Ctrl+Z)"
          disabled={!controller.canUndo}
        >
          ↩
        </button>
        <button
          className={styles.button}
          onClick={onRedo ?? (() => controller.redo())}
          title="Redo (Ctrl+Y)"
          disabled={!controller.canRedo}
        >
          ↪
        </button>
      </div>

      <div className={styles.spacer} />

      <button
        className={`${styles.button} ${styles.saveButton}`}
        onClick={onSave}
        disabled={!dirty || saving}
        title={`${SAVE} (Ctrl+S)`}
      >
        {saving ? SAVING : dirty ? SAVE : SAVED}
      </button>

        <div className={styles.menuContainer} ref={menuRef}>
          <button
            className={styles.button}
            onClick={() => setMenuOpen((prev) => !prev)}
            title={NOTE_MENU}
          >
            ⋮
          </button>
          {menuOpen && (
            <div className={styles.menu}>
              <button
                className={styles.menuItem}
                onClick={() => { setMenuOpen(false); onToggleShowCompleted() }}
              >
                <span className={styles.menuIcon}>{showCompleted ? '☑' : '☐'}</span>
                {SHOW_COMPLETED}
              </button>
              {isDeleted ? (
                onRestore && (
                  <button
                    className={styles.menuItem}
                    onClick={() => { setMenuOpen(false); onRestore() }}
                  >
                    <span className={styles.menuIcon}>&#x21A9;</span>
                    {RESTORE_NOTE}
                  </button>
                )
              ) : (
                onDelete && (
                  <button
                    className={`${styles.menuItem} ${styles.menuItemDanger}`}
                    onClick={() => { setMenuOpen(false); onDelete() }}
                  >
                    <span className={styles.menuIcon}>&#x1F5D1;</span>
                    {DELETE_NOTE}
                  </button>
                )
              )}
            </div>
          )}
        </div>
    </div>
  )
}
