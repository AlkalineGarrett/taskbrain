# Refactoring Workflow

The recipe we followed for backlog item #1 (`NoteEditorScreen.tsx` extraction). Apply it to subsequent items so the pattern stays consistent. Each phase has gotchas inline — read them before you trip over them again.

## Phase 1 — Audit

- Spawn an `Explore` agent to scan for quality issues (largest files, deepest nesting, mixed concerns, duplication). Give it the refactoring guidance from `CLAUDE.md` so its severity tiering matches the project's bar.
- Output: a prioritized list of items, each with file + line numbers and a concrete suggestion.
- The list lives in `docs/refactoring-backlog.md`. Keep it as a checklist; mark items off as they ship.

## Phase 2 — Plan and track the chosen item

- Pick the top item. Read the target file before planning extractions — the agent's summary is a starting hypothesis, not a contract.
- Break the work into sub-steps (one per extracted unit). Create a `TaskCreate` for each. This list is session-scoped; the master list above survives across conversations.
- Sketch each extraction's signature (args / return) before writing it. If two extractions share state or refs, decide who owns what now, not later.

## Phase 3 — Extract incrementally

- Order extractions by independence and risk: most-isolated first, largest/most-coupled last.
- Batch independent extractions and write them in parallel (`Write` calls in one message). Then rewrite the host file in a single pass.
- Run typecheck after each batch. Don't wait until the end — late errors stack and become hard to attribute.
- After the host file is rewritten, run the full test suite. Behavior parity is the point of the refactor.

**Gotchas:**
- Don't fabricate justifications for `require()` over `import` to avoid imagined circular issues. If the original code imported it directly, keep it that way.
- When extracting hooks that need event-handler-style fresh reads, keep both a state and a ref (state for renders, ref for handlers). See `useActiveEditorSession.ts` and the note in `CLAUDE.md`.

## Phase 4 — Test the moved logic

- One test file per extracted unit. Mirror the directory layout under `__tests__/`.
- Look at neighboring tests for patterns (`vitest.config.ts`, `setup.ts`, factories). Reuse shared factories like `__tests__/factories.ts` rather than rolling fresh ones.
- Drive tests through the public API of the unit, not its internals. If a test wants to assert on something only reachable via a private path, the API probably needs to change — consider that before adding the path back.
- Run the new tests in isolation first (fast feedback), then the full suite once at the end.

**Gotchas:**
- `vi.mock` factories are hoisted. Top-level variables referenced inside the factory are not yet defined when it runs. Use `vi.hoisted({ ... })` for shared spies.
- jsdom doesn't implement `document.elementsFromPoint` or `navigator.clipboard.writeText`. Stub them with `vi.fn()` (assigned directly, not via `vi.spyOn`, since you can't spy on a missing method).
- `vi.spyOn(...)` lets the original method run unless you call `.mockImplementation(...)`. For methods that touch missing browser APIs (e.g. `cutSelection` → `clipboard.writeText`), always supply an implementation or you'll get cryptic uncaught exceptions during otherwise-passing tests.
- React 19 batches state updates: `partial-error` followed by an auto-reset effect to `idle` may be observed as `idle` only. If the contract is "user is informed of failure," assert on the side effect (e.g. `console.error` was called) rather than chasing transient state.
- A `useState` setter with the same value (or same object reference) skips re-renders. If a hook needs to force a re-render despite no value change (e.g. notifying observers of selection changes), use a version counter.

## Phase 5 — Clean up unrelated issues found along the way

- Refactors expose dead code. After the main work, look at any new TS warnings, unused imports, or now-empty branches.
- Trace dead prop chains end-to-end. A single unused destructure can ripple back through 5 components — fix the whole chain in one pass.
- This is also when `tsc -b` warnings unrelated to the refactor get cleared. The line-count win from removing dead code often dwarfs the original extraction.

## Phase 6 — Simplify pass

- Run the `/simplify` skill (3 review agents in parallel: reuse, quality, efficiency). Pass them the diff via `git diff > /tmp/diff` plus full content of new files.
- Aggregate findings. Treat them as suggestions, not directives. For each:
  - Verify against the actual code before acting (agents often miss invariants like in-place mutation, useCallback `[]` deps stability, dual state/ref patterns).
  - Fix real wins. Skip clear false positives — note them in your reply, don't argue with them.
  - If a suggestion would change behavior (e.g. adding defensive guards), default to skip per `CLAUDE.md` (don't validate scenarios that can't happen).
- Verify with typecheck + full tests after fixes.

## Phase 7 — Document defensively

- For each false positive a review agent flagged: add a brief WHY comment at the spot. Future agents will re-flag the same thing without it. See the comments in `useCompletedLineDisplay.ts`, `useActiveEditorSession.ts`, `useDirectiveMutations.ts`, and `useGutterRouting.ts` for examples.
- For project-wide invariants that affect refactoring decisions (mutation-in-place, dual state/ref, etc.), add a note to `CLAUDE.md` under *Important Patterns*. CLAUDE.md is loaded into every conversation, so keep additions tight.
- Don't comment WHAT the code does — names should do that. Only document non-obvious WHYs and invariants that would surprise a reader.

## Phase 8 — Wrap up

- Mark the backlog item done in `docs/refactoring-backlog.md`. Note the concrete win (line counts, files affected, test delta).
- If new issues were discovered during the work, add them to the backlog with severity. Don't queue them silently in your head.
- Commit only when the user explicitly asks. Use a heredoc commit message that describes the *why*, not the *what*.

## A few rules of thumb

- **Trust agent output, but verify.** Their summaries describe intent, not always the code. Especially: read the diff yourself before acting on a "fix this" suggestion.
- **Don't preserve dead code "just in case."** If a prop, type, or callback has no real callers after a refactor, delete it.
- **Per-batch verification beats end-of-task verification.** Catch typecheck failures while the relevant context is still in your head.
- **Tests reveal API smells.** If you keep needing private hooks/internals to test something, the unit's public surface is wrong.
