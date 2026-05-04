# Firestore billing-fix deploy notes

Companion to the May 2026 work that brought the local `FirestoreUsage` report
closer to the Firebase Console number. The code changes (instrumentation,
30-day report retention, `userId` stamping on directiveResults) take effect on
the next app build and ship through normal channels. The items below are the
ones that don't — they need to be done manually in the Firebase console or
verified by hand.

## 1. Deploy `firestore.rules`

The repo's `firebase.json` points the rules path at `firestore.rules.emulator`
(permissive, signed-in-only) so `firebase deploy --only firestore:rules` would
ship that to prod and open the database to any authenticated user. The
production rules live in `firestore.rules` and are deployed manually:

1. Open the [Firebase console](https://console.firebase.google.com/) → your
   project → **Firestore Database** → **Rules** tab.
2. Paste the full contents of `firestore.rules` into the editor.
3. Click **Publish**.

### What changed in the rules

The `notes/{noteId}/directiveResults/{directiveHash}` block used to do a
`get(/databases/.../notes/$(noteId))` to verify ownership. That `get()` is a
billed read on every directive results read **and** every write — every
read/write of a directive result paid for an extra parent-note fetch.

The new rule enforces ownership against the doc's own `userId` field, which
the app now stamps on every write. `list` (snapshot listener) trusts the
user-allowlist gate so the listener doesn't have to add a `.where('userId',
'==', uid)` filter.

### Legacy directiveResults docs

The new rule has a transitional clause:

```
allow get, update, delete: if isAuthenticated() &&
  (!('userId' in resource.data) || existingDataBelongsToUser());
```

That `!('userId' in resource.data)` lets legacy docs (written before the
userId stamp landed) stay readable. They get re-written with userId next
time the directive runs, so the legacy population shrinks naturally.

If you'd rather not wait, run a one-time backfill (cloud function, gcloud
script, or a quick admin SDK script) that reads each `directiveResults` doc,
reads its parent note's `userId`, and writes the userId field on the
directiveResults doc. Then tighten the rule by deleting the
`!('userId' in resource.data) ||` clause. Not necessary — just an option.

## 2. Verify the billing drop

Wait at least one day after deploying so the console catches up, then:

1. Open the app → tap the **Usage** report (Android) or run
   `firestoreUsage.getReport()` from the browser console (web).
2. Look at the **Last 30 days (compare to Firebase console)** section — that's
   the window the console's monthly billing rolls up over.
3. Compare to the console's **Usage and billing** → **Reads** number.

The local report still under-counts by:

- **Multi-device aggregation**: the console adds reads from every browser
  profile, every Android install, every device. The local report is per
  storage. Sum across your active clients to get a real comparison.
- **Hard-close write loss**: up to 30 seconds of unsaved increments are lost
  when a tab closes or the Android process is killed (the persist debounce
  is 30s — see `FirestoreUsage.{ts,kt}`).
- **Cross-tab race on web**: multiple tabs each load localStorage at
  construction and persist debounced; last writer wins, so concurrent tabs
  silently undercount. Not fixed in this pass — see follow-ups below.
- **Server-side reads from outside the apps**: the Firebase Console itself
  charges reads when you browse data; admin SDK scripts do too.

If the numbers are still off by more than the multi-device explanation can
account for, look for new uninstrumented code paths — every `getDoc` /
`getDocs` / `setDoc` / `updateDoc` / `deleteDoc` / batch / transaction must
call `firestoreUsage.recordRead/recordWrite`. The
[`docs/firestore-efficiency.md`](firestore-efficiency.md) anti-patterns
checklist is the screen for that.

## 3. Follow-ups not done in this pass

- **Cross-tab race on web** (item 4 in the original analysis): fix
  `FirestoreUsage.ts` to read-modify-write localStorage on persist (or use a
  `BroadcastChannel` so tabs share an in-memory counter).
- **Backfill legacy directiveResults docs** with `userId` so the rule's
  transitional clause can be removed.
