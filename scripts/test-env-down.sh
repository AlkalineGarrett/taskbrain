#!/usr/bin/env bash
# Stop the local test environment cleanly.
#
#   ./scripts/test-env-down.sh           # Firebase emulator only
#   ./scripts/test-env-down.sh --with-avd # also kill the Android AVD
#
# Idempotent: safe to run when nothing is up.

set -euo pipefail

LOG_DIR="${TMPDIR:-/tmp}"
EMULATOR_PID_FILE="$LOG_DIR/firebase-emulators.pid"

WITH_AVD=false
for arg in "$@"; do
  case "$arg" in
    --with-avd) WITH_AVD=true ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# ── Firebase emulator ─────────────────────────────────────────────────────────
echo "==> Firebase emulator"
killed=false
if [ -f "$EMULATOR_PID_FILE" ]; then
  PID="$(cat "$EMULATOR_PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    # SIGINT lets the Firebase CLI shut down its Java children gracefully.
    kill -INT "$PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$PID" 2>/dev/null; then
      kill -KILL "$PID" 2>/dev/null || true
    fi
    killed=true
  fi
  rm -f "$EMULATOR_PID_FILE"
fi

# Fallback / belt-and-braces: kill anything still listening on the emulator
# ports (a previous run that exited without writing the PID file, or the Java
# child whose parent we just killed).
for port in 8080 9099 4000 4400; do
  pids="$(lsof -ti :$port 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    echo "$pids" | xargs kill 2>/dev/null || true
    killed=true
  fi
done

if $killed; then
  echo "    stopped"
else
  echo "    not running"
fi

# ── Android AVD (optional) ────────────────────────────────────────────────────
if ! $WITH_AVD; then
  exit 0
fi

echo "==> Android AVD"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB_BIN="$ANDROID_SDK/platform-tools/adb"

if [ -x "$ADB_BIN" ] && "$ADB_BIN" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  "$ADB_BIN" emu kill 2>/dev/null || true
  echo "    sent kill"
else
  echo "    not running"
fi
