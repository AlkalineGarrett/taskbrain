# Editor callbacks: intent vs. notification

A method called from a "system says X happened" callback must not, as a side effect, request that X happen again.

This applies to focus, selection, scroll, IME composition — any system-driven event with a paired observer/requester pair.

## The trap (concrete: focus on Android)

Compose pattern: `LaunchedEffect(focusVersion)` calls `focusRequester.requestFocus()`, which fires `onFocusChanged(true)`. If `onFocusChanged` calls a method that bumps `focusVersion`, the effect re-fires, calling `requestFocus` again, and the loop pumps until something else stops it. This was a real production bug — the IME show storm at ~100 Hz killed the activity via the watchdog.

## Rule

When you write a new mutator on the editor controller or state, decide up front whether it's:
- **Intent** ("I want X to happen") — bumps the version counter that fires the LaunchedEffect / observer.
- **Notification** ("the system reports X happened") — does NOT bump. The thing already happened; bumping requests it again, looping.

If a method is called from both kinds of caller, **split it**. One name, one meaning.

## Read-side / write-side parity

The same principle drove the `focusVersion` / `stateVersion` split (passive content bumps shouldn't re-grab IME). When you split version counters, **honor the split on both sides**: the read side (`LaunchedEffect` / observer keys) AND the write side (which counter a mutator bumps). A split that's only enforced on one side will pump on the other.

## Naming convention

Where this distinction exists, use complementary names:
- `markLineFocused(idx)` — notification, no bump. Called from `onFocusChanged`.
- `focusLine(idx)` — intent, bumps `focusVersion`. Called when programmatically moving focus.

Same idea generalizes to selection, scroll, etc.: `markX` for the notifications, plain `x` (or `setX`) for the intent.
