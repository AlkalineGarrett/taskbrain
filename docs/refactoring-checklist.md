# Refactoring checklist

Things to look for when refactoring. The end-to-end workflow is in `docs/refactoring-workflow.md`; the backlog is in `docs/refactoring-backlog.md`.

- Look to make the code and design "elegant".
- Consolidate repetition of code.
- Consolidate repetition of patterns based on the same concept.
  - If the same groupings of parameters are passed around in multiple places, encapsulate them.
- Break apart long functions (anything longer than 50 lines is suspicious; the more indented, the more it needs to be broken apart).
- Break apart long files.
- Avoid deeply nested code (anything 4 or more levels deep is suspicious, especially the more lines of code it is).
- Make sure each unit (file, function, class) has a clear responsibility and not multiple, and at a single level of granularity (think: don't combine paragraph work with character work).
- Define constants or config instead of hard-coded numbers.
- Move logic out of display classes.
- Look for ways that different use cases have slightly different logic, when there is no inherent reason for them to be different, and merge them.
- Decouple units by using callbacks, etc., so that classes refer to each other directly less often.
- Look for places with too many edge cases and come up with more robust, general logic instead.
