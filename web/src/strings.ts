/**
 * User-facing text strings.
 * Keep in sync with Android: app/src/main/res/values/strings.xml
 */

// App (Android: app_name)
export const APP_NAME = 'TaskBrain'

// Navigation (Android: title_current_note, title_note_list, title_notifications, title_schedules)
export const NAV_CURRENT_NOTE = 'Current note'
export const NAV_ALL_NOTES = 'All notes'
export const NAV_ALARMS = 'Alarms'
export const NAV_SCHEDULES = 'Schedules'

// Auth (Android: sign_in_google, sign_out)
export const SIGN_IN_WITH_GOOGLE = 'Sign in with Google'
export const SIGNING_IN = 'Signing in...'
export const SIGN_OUT = 'Sign out'
export const LOGIN_SUBTITLE = 'ADHD-friendly task management'

// Common actions (Android: action_ok, action_cancel, action_close, etc.)
export const OK = 'OK'
export const CANCEL = 'Cancel'
export const CLOSE = 'Close'
export const DISMISS = 'Dismiss'
export const DELETE = 'Delete'
export const UPDATE = 'Update'
export const NEXT = 'Next'
export const CLEAR = 'Clear'
export const CONFIRM = 'Confirm'
export const COPY = 'Copy'
export const CUT = 'Cut'
export const SELECT_ALL = 'Select All'
export const UNSELECT = 'Unselect'
export const PASTE = 'Paste'
export const SEND = 'Send'
export const COLLAPSE = 'Collapse'
export const REFRESH = 'Refresh'
// Firestore usage diagnostic (Android: action_firestore_usage, firestore_usage_*)
export const FIRESTORE_USAGE = 'Usage'
export const FIRESTORE_USAGE_TITLE = 'Firestore Usage'
export const FIRESTORE_USAGE_CLOSE = 'Close'
export const FIRESTORE_USAGE_RESET = 'Reset counters'

// Note list (Android: action_add_note, action_delete_note, action_restore_note, etc.)
export const ADD_NOTE = 'Add note'

// Note list sort modes (Android: sort_recent, sort_frequent, sort_consistent)
export const SORT_RECENT = 'Recent'
export const SORT_FREQUENT = 'Frequent'
export const SORT_CONSISTENT = 'Consistent'
export const DELETE_NOTE = 'Delete note'
export const RESTORE_NOTE = 'Restore note'
export const SHOW_DELETED = 'Show deleted'
export const SECTION_DELETED_NOTES = 'Deleted notes'
export const NO_NOTES_FOUND = 'No notes found'
export const NO_DELETED_NOTES = 'No deleted notes'
export const EMPTY_NOTE = 'Empty Note'

// Status (Android: action_save, status_saved, status_unsaved, status_saving, status_needs_fix)
export const SAVE = 'Save'
export const CREATE = 'Create'
export const SAVING = 'Saving\u2026'
export const SAVED = 'Saved'
export const UNSAVED = 'Unsaved'
export const NEEDS_FIX = 'Fix'
export const TAB_NEEDS_FIX_INDICATOR = 'Note was auto-healed — save to persist the fix'
export const LOADING = 'Loading\u2026'
export const LOADING_NOTE = 'Loading note\u2026'

// Error strings (Android: error_title, error_unknown, etc.)
export const ERROR_TITLE = 'Error'
export const ERROR_UNKNOWN = 'Unknown error'
export const ERROR_AN_ERROR_OCCURRED = 'An error occurred'
export const ERROR_LOAD = 'Load Error'
export const ERROR_SAVE = 'Save Error'
export const ERROR_CREATE_NOTE = 'Create Note Error'
export const ERROR_TABS = 'Tabs Error'
export const ERROR_ALARM = 'Alarm Error'

// Note actions (Android: action_delete_note_confirm_title, action_delete_note_confirm_message)
export const DELETE_NOTE_CONFIRM_TITLE = 'Delete note?'
export const DELETE_NOTE_CONFIRM_MESSAGE = 'This note will be moved to the deleted notes section.'
export const CLEAR_DELETED = 'Clear deleted'
export const CLEAR_DELETED_CONFIRM_TITLE = 'Permanently delete all?'
export const CLEAR_DELETED_CONFIRM_MESSAGE =
  'Every note in the deleted section will be removed from Firestore. This cannot be undone.'
export const clearedDeletedCount = (count: number) => `Cleared ${count} deleted note(s)`
export const NOTE_MENU = 'Note menu'

// Note editor (Android: new_note, close_tab, tab_menu, empty_line)
export const NEW_NOTE = 'New Note'
export const CLOSE_TAB = 'Close tab'
export const TAB_MENU = 'Tab menu'
export const EMPTY_LINE = '(empty line)'

// Command bar (Android: command_toggle_bullet, command_toggle_checkbox, etc.)
export const COMMAND_TOGGLE_BULLET = 'Toggle bullet'
export const COMMAND_TOGGLE_CHECKBOX = 'Toggle checkbox'
export const COMMAND_INDENT = 'Indent'
export const COMMAND_UNINDENT = 'Unindent'
export const COMMAND_MOVE_UP = 'Move lines up'
export const COMMAND_MOVE_DOWN = 'Move lines down'
export const COMMAND_ADD_ALARM = 'Add alarm'

// Show completed (Android: action_show_completed, completed_count)
export const SHOW_COMPLETED = 'Show completed'
export const COMPLETED_COUNT = '(%d completed)'

// Inline editor
export const EDITING_NOTE = 'Editing note'
export const MODIFIED = 'Modified'
export const SAVE_AND_CLOSE = 'Save & Close'
export const DISCARD_UNSAVED = 'Discard unsaved changes?'

// Alarm config dialog (Android: alarm_show_upcoming, alarm_lock_screen, etc.)
export const ALARM_SHOW_UPCOMING = 'Show in Upcoming list'
export const ALARM_LOCK_SCREEN = 'Notification'
export const ALARM_URGENT = 'Lock screen'
export const ALARM_SOUND = 'Sound alarm'
export const ALARM_DUE = 'Due'
export const ALARM_MINUTES_BEFORE = '%d min before'
export const ALARM_HOURS_BEFORE = '%d hr before'
export const ALARM_AT_DUE_TIME = 'At due time'
export const ALARM_DONE = 'Done'
export const ALARM_CANCEL = 'Cancel Alarm'

// Alarm activity (Android: alarm_type_alarm, alarm_snooze_for, etc.)
export const ALARM_TYPE_ALARM = 'Alarm'
export const ALARM_TYPE_URGENT = 'Urgent'
export const ALARM_TYPE_REMINDER = 'Reminder'
export const ALARM_SNOOZE_FOR = 'Snooze for:'
export const ALARM_SNOOZE_2_MIN = '2 min'
export const ALARM_SNOOZE_10_MIN = '10 min'
export const ALARM_SNOOZE_1_HOUR = '1 hour'
export const ALARM_MARK_DONE = 'Mark Done'
export const ALARM_SKIP = 'Skip' // Android: alarm_skip — button label for skipping an alarm
export const ALARM_SNOOZE_NOTIFICATION = 'Snooze' // Android: alarm_snooze_notification
export const ALARM_DUE_FMT = 'Due %s' // Android: alarm_due_fmt

// Alarms screen (Android: no_alarms, section_past_due, etc.)
export const NO_ALARMS = 'No alarms'
export const SECTION_PAST_DUE = 'Past Due'
export const SECTION_UPCOMING = 'Upcoming'
export const SECTION_LATER = 'Later'
export const SECTION_COMPLETED = 'Completed'
export const SECTION_CANCELLED = 'Skipped'
export const ALARM_MARK_DONE_DESC = 'Mark done'
export const ALARM_CANCEL_DESC = 'Cancel'
export const ALARM_REACTIVATE = 'Reactivate'
export const ALARM_SKIPPED = 'Skipped'
export const ALARM_REOPEN = 'Re-open'
export const ALARM_UNTITLED = 'Untitled alarm'
export const ALARM_DELETE_ALL = 'Delete all alarms'
export const ALARM_DELETE_ALL_CONFIRM = 'This will permanently delete all alarms and recurring alarm templates. This cannot be undone.'

// Permission warnings (Android: permission_issues, dialog_exact_alarms_title, etc.)
export const PERMISSION_ISSUES = 'Permission Issues'
export const DIALOG_EXACT_ALARMS_TITLE = 'Exact Alarms Disabled'
export const DIALOG_NOTIFICATIONS_TITLE = 'Notifications Disabled'
export const DIALOG_SCHEDULING_TITLE = 'Alarm Scheduling Issue'
export const DIALOG_SCHEDULING_MESSAGE = 'The alarm was saved but may not trigger at the expected time.'

// Redo error (Android: redo_failed, redo_error, etc.)
export const REDO_FAILED = 'Redo Failed'
export const REDO_ERROR = 'Redo Error'

// Recurrence config (Android: recurrence_repeat, recurrence_fixed_schedule, etc.)
export const RECURRENCE_REPEAT = 'Repeat'
export const RECURRENCE_FIXED_SCHEDULE = 'Fixed schedule'
export const RECURRENCE_AFTER_COMPLETION = 'After completion'
export const RECURRENCE_FREQUENCY = 'Frequency'
export const RECURRENCE_CUSTOM = 'Custom'
export const RECURRENCE_CUSTOM_INTERVAL = 'Custom interval'
export const RECURRENCE_EVERY = 'Every'
export const RECURRENCE_ON_DAYS = 'On days'
export const RECURRENCE_INTERVAL_AFTER_COMPLETION = 'Repeat interval after completion'
export const RECURRENCE_ENDS = 'Ends'
export const RECURRENCE_AFTER = 'After'
export const RECURRENCE_TIMES = 'times'
export const RECURRENCE_EDIT = 'Edit recurrence'

// Alarm mode toggle (Android: alarm_mode_instance, alarm_mode_recurrence, alarm_also_update_next, alarm_also_update_recurrence)
export const ALARM_MODE_INSTANCE = 'This instance'
export const ALARM_MODE_RECURRENCE = 'Recurrence'
export const ALARM_ALSO_UPDATE_NEXT = 'Also update next instance'
export const ALARM_ALSO_UPDATE_RECURRENCE = 'Also update recurrence'

// Recurrence descriptions (Android: recurrence_desc_daily, recurrence_desc_weekdays, etc.)
export const RECURRENCE_DESC_DAILY = 'Repeats daily'
export const RECURRENCE_DESC_WEEKDAYS = 'Repeats on weekdays'
export const RECURRENCE_DESC_WEEKLY = 'Repeats weekly'
export const RECURRENCE_DESC_MONTHLY = 'Repeats monthly'
export const RECURRENCE_DESC_UNKNOWN = 'Repeating alarm'

// Date/time picker (Android: datetime_not_set, datetime_select_time)
export const DATETIME_NOT_SET = 'Not set'
export const DATETIME_DATE = 'Date'
export const DATETIME_TIME = 'Time'
export const DATETIME_SELECT_TIME = 'Select time'

// Schedules screen (Android: schedule_unknown_note, schedule_manual_run, etc.)
export const SCHEDULE_UNKNOWN_NOTE = 'Unknown note'
export const SCHEDULE_UNKNOWN = 'Unknown'
export const SCHEDULE_MANUAL_RUN = 'Manual run'
export const SCHEDULE_SUCCESS = 'Success'
export const SCHEDULE_FAILED = 'Failed'

// Directive
export const DIRECTIVE_PLACEHOLDER = 'Directive source...'
export const EMPTY_VIEW = '[empty view]'

// Save error banner (web-only; Android surfaces save warnings via a dialog)
export const SAVE_ERROR_BANNER = 'Your changes may not have been saved'
export const SAVE_ERROR_DISMISS = 'Dismiss'

// Sync error (Android: warning_sync_error)
export const SYNC_ERROR_BANNER = 'Note sync interrupted — changes from other devices may not appear'

// Search (Android: action_search, search_hint, search_filter_name, search_filter_content, search_go, search_no_results, search_history, search_history_button)
export const SEARCH = 'Search'
export const SEARCH_HINT = 'Search notes\u2026'
export const SEARCH_FILTER_NAME = 'Name'
export const SEARCH_FILTER_CONTENT = 'Content'
export const SEARCH_GO = 'Go'
export const SEARCH_NO_RESULTS = 'No matching notes'
export const SEARCH_HISTORY = 'Recent searches'
export const SEARCH_HISTORY_BUTTON = 'Search history'

// Offline indicators (Android: offline_banner_message, synced_banner_message, ai_unavailable_offline)
export const OFFLINE_BANNER_MESSAGE = 'You\u2019re offline \u2014 changes will sync when reconnected'
export const SYNCED_BANNER_MESSAGE = 'Changes synced'
export const AI_UNAVAILABLE_OFFLINE = 'AI unavailable offline'

// Tabs
export const EMPTY_TAB = '(empty)'
