import { createContext } from 'react'

/**
 * Carries the parent editor's `showCompleted` setting down the React tree.
 * Read by `ViewNoteSection` inside a view directive so the parent's setting
 * overrides each embedded note's own setting. Null when not inside a parent
 * editor context (i.e., the note is being viewed standalone).
 */
export const ParentShowCompletedContext = createContext<boolean | null>(null)
