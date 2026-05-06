#!/usr/bin/env bash
# Start the local test environment idempotently.
#
#   ./scripts/test-env-up.sh            # Firebase emulator only (web tests)
#   ./scripts/test-env-up.sh --with-avd # Firebase emulator + Android AVD
#
# Re-running is a no-op if everything is already up.
# Stop with ./scripts/test-env-down.sh.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${TMPDIR:-/tmp}"
EMULATOR_LOG="$LOG_DIR/firebase-emulators.log"
EMULATOR_PID_FILE="$LOG_DIR/firebase-emulators.pid"
AVD_LOG="$LOG_DIR/avd.log"

WITH_AVD=false
for arg in "$@"; do
  case "$arg" in
    --with-avd) WITH_AVD=true ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# ── Firebase emulator ─────────────────────────────────────────────────────────
echo "==> Firebase emulator"
if curl -sf http://localhost:8080 >/dev/null 2>&1; then
  echo "    already running on :8080"
else
  echo "    starting (logs: $EMULATOR_LOG)"
  (cd "$REPO_ROOT" && nohup firebase emulators:start >"$EMULATOR_LOG" 2>&1 &
    echo $! >"$EMULATOR_PID_FILE")
  for _ in $(seq 1 60); do
    if curl -sf http://localhost:8080 >/dev/null 2>&1 \
       && curl -sf http://localhost:9099 >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  if ! curl -sf http://localhost:8080 >/dev/null 2>&1; then
    echo "    FAILED to start within 60s; tail of $EMULATOR_LOG:" >&2
    tail -n 20 "$EMULATOR_LOG" >&2 || true
    exit 1
  fi
  echo "    ready on :8080 (firestore), :9099 (auth), :4000 (ui)"
fi

# ── Android AVD (optional) ────────────────────────────────────────────────────
if ! $WITH_AVD; then
  echo "==> Done (AVD skipped; pass --with-avd for instrumentation tests)"
  exit 0
fi

echo "==> Android AVD"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
EMULATOR_BIN="$ANDROID_SDK/emulator/emulator"
ADB_BIN="$ANDROID_SDK/platform-tools/adb"

if ! [ -x "$EMULATOR_BIN" ]; then
  echo "    emulator binary not found at $EMULATOR_BIN" >&2
  exit 1
fi

# Anything in `device` state (not `offline`, not `unauthorized`) counts as up.
if "$ADB_BIN" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "    already running"
else
  AVD_NAME="$("$EMULATOR_BIN" -list-avds | head -n 1)"
  if [ -z "$AVD_NAME" ]; then
    echo "    no AVDs found; create one with avdmanager" >&2
    exit 1
  fi
  echo "    booting $AVD_NAME (logs: $AVD_LOG)"
  nohup "$EMULATOR_BIN" -avd "$AVD_NAME" >"$AVD_LOG" 2>&1 &
  for _ in $(seq 1 180); do
    if "$ADB_BIN" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
      break
    fi
    sleep 1
  done
  if ! "$ADB_BIN" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "    FAILED to boot within 180s; tail of $AVD_LOG:" >&2
    tail -n 20 "$AVD_LOG" >&2 || true
    exit 1
  fi
  # Wait for full boot completion, not just adb registration.
  "$ADB_BIN" wait-for-device
  for _ in $(seq 1 60); do
    if [ "$("$ADB_BIN" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      break
    fi
    sleep 1
  done
  echo "    ready"
fi

echo "==> Done"
