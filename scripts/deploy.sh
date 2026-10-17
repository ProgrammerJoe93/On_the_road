#!/usr/bin/env bash
# Builds the debug APK, installs it on the emulator (starting one if needed) and opens the app.
# Extra arguments go to Gradle, e.g. --offline.
#   --headless   start the emulator without a window
set -euo pipefail
source "$(dirname "$0")/common.sh"

headless=""
args=()
for arg in "$@"; do
  if [[ "$arg" == --headless ]]; then
    headless=--headless
  else
    args+=("$arg")
  fi
done

if ! emulator_running; then
  "$(dirname "$0")/emulator.sh" $headless &
  emulator_pid=$!
fi
gradle :app:assembleDebug "${args[@]}"
[[ -n "${emulator_pid:-}" ]] && wait "$emulator_pid"
ensure_emulator

"$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" shell am start -n "$APP_ID/.ui.MainActivity" >/dev/null
echo "On the Road is running on the emulator."
