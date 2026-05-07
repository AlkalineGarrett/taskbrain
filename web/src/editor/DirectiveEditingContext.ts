import { createContext, useContext } from 'react'

/**
 * Globally tracks which directive segment is currently in inline-edit
 * mode (showing a `DirectiveEditRow`). Lifted out of `DirectiveLineContent`
 * because the trigger comes from outside the line in some cases — most
 * notably the gear icon on a `[view find(...)]` directive's embedded
 * content (rendered as flat sibling rows of the parent line, not nested
 * inside it). Segment keys are `lineId:offset` and unique across the
 * editor, so a single string identifies any one directive being edited.
 */
export interface DirectiveEditing {
  editingKey: string | null
  setEditingKey: (key: string | null) => void
}

export const DirectiveEditingContext = createContext<DirectiveEditing>({
  editingKey: null,
  setEditingKey: () => {},
})

export function useDirectiveEditing(): DirectiveEditing {
  return useContext(DirectiveEditingContext)
}
