# Android app

Kotlin / Jetpack Compose. Source under `app/src/main/`, unit tests under `app/src/test/`, instrumentation tests under `app/src/androidTest/`.

## Build commands

Run from the repo root:

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew installDebug         # Install on the connected device
./gradlew test                 # Unit tests
./gradlew connectedAndroidTest # Instrumentation tests (needs an emulator + the Firebase emulator suite)
./gradlew clean
```

Single test class:
```bash
./gradlew test --tests "org.alkaline.taskbrain.data.NoteLineTrackerTest"
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.alkaline.taskbrain.UndeleteSkipsEarlierBatchFlowTest
```

## Running against the Firebase emulator

Bring up the shared environment first (see top-level `CLAUDE.md` — use `scripts/test-env-up.sh --with-avd`). Then point the gradle tasks at it:

```bash
./gradlew connectedAndroidTest -PuseFirebaseEmulator=true   # Run instrumentation against emulator
./gradlew installDebug -PuseFirebaseEmulator=true           # Install app pointed at emulator
```

`-PuseFirebaseEmulator=true` flips a `BuildConfig.USE_FIREBASE_EMULATOR` field; `TaskBrainApplication.onCreate` reads it to wire `useEmulator(...)` on Firestore + Auth and kick off `signInAnonymously()`. Smoke tests self-skip when the flag is off.

## Dependencies

Versions managed in `gradle/libs.versions.toml`. Key stack: Kotlin 2.1.0, Compose, Material 3, Firebase (Auth, Firestore, AI), Google Sign-In with Credential Manager.

## Testing stack

JUnit 4 + MockK for unit tests (`app/src/test/`). Compose UI test framework + emulator-backed instrumentation tests under `app/src/androidTest/`. Helpers in `EmulatorTestSupport.kt` (`requireEmulatorAndSignIn`, `seedMultiLineNote`, `descendantsByState`).

## Release

See `TODO_RELEASE.md` for signing configuration. Requires environment variables: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
