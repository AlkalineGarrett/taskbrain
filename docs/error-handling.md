# Error handling and user feedback

The app should generally inform users when problems occur rather than silently failing or recovering. The app is in an alpha stage and the developer is the main user, so they need to immediately be informed so they can fix the issue. They will not be confused about getting implementation-specific details.

- **Show warning dialogs** when operations fail or produce unexpected results, even if the app can recover automatically.
- **Explain what happened** in user-friendly terms, including what automatic recovery was attempted.
- **Indicate potential inconsistency** if recovery may have left things in a partial state (e.g., "Consider saving and reloading").
- Use `AlertDialog` for warnings; see existing patterns in `CurrentNoteScreen.kt` (e.g., `redoRollbackWarning`, `schedulingWarning`).

This principle applies especially to:
- Failed async operations (Firebase, alarms, etc.)
- Automatic rollbacks or cleanup after failures
- Permission issues that affect functionality

## Never silently swallow errors

- Do not use empty `catch` blocks, `/* ignore */` comments, or `.getOrNull()` without handling the failure case.
- Every `.onFailure` / `catch` must either propagate the error to a caller that surfaces it, or surface it directly.
- In ViewModels: set error state (e.g., `_alarmError.value`) that the UI observes.
- In BroadcastReceivers / background services: use `AlarmErrorActivity.show(context, title, message)` to display errors.
- Logging (`Log.e`) alone is not sufficient — errors must be user-visible.
