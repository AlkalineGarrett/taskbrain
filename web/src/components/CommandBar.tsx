import { useState, useRef, useEffect } from 'react'
import type { EditorController } from '@/editor/EditorController'
import {
  SAVE, SAVING, SAVED, NEEDS_FIX,
  COMMAND_TOGGLE_BULLET, COMMAND_TOGGLE_CHECKBOX,
  COMMAND_INDENT, COMMAND_UNINDENT,
  COMMAND_MOVE_UP, COMMAND_MOVE_DOWN,
  DELETE_NOTE, RESTORE_NOTE, NOTE_MENU,
  SHOW_COMPLETED,
} from '@/strings'
import styles from './CommandBar.module.css'

/** Material Design icon wrapper — renders an SVG at 20x20 inside the button. */
function MdIcon({ path, title }: { path: string; title?: string }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      {title && <title>{title}</title>}
      <path d={path} />
    </svg>
  )
}

// Material Design icon paths (from Android vector drawables)
const IC_BULLET = "M4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,18.5h14v-2H7v2zM7,12.5h14v-2H7v2zM7,4.5v2h14v-2H7z"
const IC_CHECKBOX = "M1.5,3.5h5v5h-5v-5M2.5,4.5v3h3v-3h-3M1.5,9.5h5v5h-5v-5M2.5,10.5v3h3v-3h-3M1.5,15.5h5v5h-5v-5M2.5,16.5v3h3v-3h-3M7,18.5h14v-2H7v2M7,12.5h14v-2H7v2M7,4.5v2h14v-2H7z"
const IC_UNINDENT = "M11,17h10v-2H11v2zM3,12l4,4V8l-4,4zM3,21h18v-2H3v2zM3,3v2h18V3H3zM11,9h10V7H11v2zM11,13h10v-2H11v2z"
const IC_INDENT = "M3,21h18v-2H3v2zM3,8v8l4,-4 -4,-4zM11,17h10v-2H11v2zM3,3v2h18V3H3zM11,9h10V7H11v2zM11,13h10v-2H11v2z"
const IC_ARROW_UP = "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z"
const IC_ARROW_DOWN = "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z"
const IC_UNDO = "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z"
const IC_REDO = "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z"

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'partial-error'

interface CommandBarProps {
  controller: EditorController
  onSave: () => void
  onUndo?: () => void
  onRedo?: () => void
  canUndo?: boolean
  canRedo?: boolean
  onDelete?: () => void
  onRestore?: () => void
  isDeleted?: boolean
  anyDirty: boolean
  saveStatus: SaveStatus
  needsFix?: boolean
  showCompleted: boolean
  onToggleShowCompleted: () => void
}

export function CommandBar({
  controller, onSave, onUndo, onRedo, canUndo, canRedo, onDelete, onRestore, isDeleted, anyDirty, saveStatus,
  needsFix, showCompleted, onToggleShowCompleted,
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
    <div className={styles.bar} onMouseDown={(e) => e.preventDefault()}>
      <div className={styles.group}>
        <button
          className={styles.button}
          onClick={() => controller.toggleBullet()}
          title={COMMAND_TOGGLE_BULLET}
        >
          <MdIcon path={IC_BULLET} />
        </button>
        <button
          className={styles.button}
          onClick={() => controller.toggleCheckbox()}
          title={COMMAND_TOGGLE_CHECKBOX}
        >
          <MdIcon path={IC_CHECKBOX} />
        </button>
      </div>

      <div className={styles.group}>
        <button
          className={styles.button}
          onClick={() => controller.unindent()}
          title={COMMAND_UNINDENT}
        >
          <MdIcon path={IC_UNINDENT} />
        </button>
        <button
          className={styles.button}
          onClick={() => controller.indent()}
          title={COMMAND_INDENT}
        >
          <MdIcon path={IC_INDENT} />
        </button>
      </div>

      <div className={styles.group}>
        <button
          className={`${styles.button} ${controller.getMoveUpState().isWarning ? styles.warning : ''}`}
          onClick={() => controller.moveUp()}
          title={COMMAND_MOVE_UP}
          disabled={!controller.getMoveUpState().isEnabled}
        >
          <MdIcon path={IC_ARROW_UP} />
        </button>
        <button
          className={`${styles.button} ${controller.getMoveDownState().isWarning ? styles.warning : ''}`}
          onClick={() => controller.moveDown()}
          title={COMMAND_MOVE_DOWN}
          disabled={!controller.getMoveDownState().isEnabled}
        >
          <MdIcon path={IC_ARROW_DOWN} />
        </button>
      </div>

      <div className={styles.group}>
        <button
          className={styles.button}
          onClick={onUndo}
          title="Undo (Ctrl+Z)"
          disabled={canUndo !== undefined ? !canUndo : !controller.canUndo}
        >
          <MdIcon path={IC_UNDO} />
        </button>
        <button
          className={styles.button}
          onClick={onRedo}
          title="Redo (Ctrl+Y)"
          disabled={canRedo !== undefined ? !canRedo : !controller.canRedo}
        >
          <MdIcon path={IC_REDO} />
        </button>
      </div>

      <div className={styles.spacer} />

      <button
        className={`${styles.button} ${styles.saveButton} ${needsFix ? styles.saveButtonNeedsFix : ''}`}
        onClick={onSave}
        disabled={saveStatus === 'saving' || (!anyDirty && !needsFix && saveStatus !== 'partial-error')}
        title={`${SAVE} (Ctrl+S)`}
      >
        {saveStatus === 'saving'
          ? SAVING
          : anyDirty || saveStatus === 'partial-error'
          ? SAVE
          : needsFix
          ? NEEDS_FIX
          : SAVED}
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
