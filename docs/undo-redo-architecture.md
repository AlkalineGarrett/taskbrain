# Undo/Redo Architecture

**All discrete editing operations must flow through `EditorController`**, which handles undo boundary management via an operation-based system:

1. **EditorController** is the single channel for state modifications (mutation methods on `EditorState` are `internal`)
2. **OperationType enum** classifies operations: `COMMAND_BULLET`, `COMMAND_CHECKBOX`, `COMMAND_INDENT`, `PASTE`, `CUT`, `DELETE_SELECTION`, `CHECKBOX_TOGGLE`, `ALARM_SYMBOL`
3. **Operation executor** (`executeOperation`) wraps operations with proper pre/post undo handling

**When adding new operations that modify editor content:**
- Add a new `OperationType` if it has distinct undo semantics
- Add a method to `EditorController` that wraps the operation with `executeOperation()`
- Call the controller method from UI code (never call `EditorState` mutation methods directly)

**Key questions to ask the developer:**
- Should this operation create its own undo boundary, or be grouped with adjacent edits?
- For command bar buttons: should consecutive presses be grouped (like indent) or separate (like bullet)?
- If the operation creates side effects (like alarms), should those be undoable?
- Does the operation need special handling for redo (e.g., recreating external resources)?

See `app/src/main/java/org/alkaline/taskbrain/ui/currentnote/requirements.md` for the full undo/redo specification and implementation details.
