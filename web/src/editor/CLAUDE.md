# Editor (web)

Editor state (`EditorState`), the controller (`EditorController`) that owns all mutations, paste handling, line state, undo manager, and the inline-edit session machinery.

## Cross-platform principles (load these too)

@docs/undo-redo-architecture.md
@docs/editor-callback-intent-vs-notification.md
@docs/live-cross-platform-sync.md

## Web-specific notes

**`editorState.lines` is mutable in place** — it's a stable array reference; individual `LineState` objects mutate when the user edits.

- React `useMemo` / `useEffect` deps cannot use `editorState.lines` (or anything keyed by its reference) as a trigger — the reference never changes. Key memos on a derived value, e.g. `lines.map(l => l.text).join('\n')`, with `// eslint-disable-next-line react-hooks/exhaustive-deps`.
- This is intentional, not a bug. Don't "fix" it by wrapping `lines.map(...)` in a `useMemo` keyed on `editorState.lines` — the memo would never invalidate.

**Active-editor state and ref both exist on purpose (`useActiveEditorSession`):**

- The `activeSession` state drives renders and derived values; the `activeSessionRef` gives event handlers (keyboard, gutter) a stale-closure-free read at fire time.
- Don't collapse them — removing the ref forces handlers to re-register on every activation; removing the state breaks downstream re-renders.

**Deletion source tracking** — `EditorController` records a per-line `DeletionSource` at every line-removal site (paste-replace, selection-delete, move, backspace-merge, delete-merge). The save layer reads `controller.consumePendingSoftDeletes()` and stamps a source-tagged `deletionBatchId` on each removed doc. Reset on external content reload.
