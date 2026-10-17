# Shared helpers for the scripts in this directory. Source it; don't run it.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="com.joegineered.ontheroad"
AVD="${OTR_AVD:-otr_test}"

# The SDK location: ANDROID_HOME, then sdk.dir in local.properties, then the default.
sdk_dir() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    echo "$ANDROID_HOME"
  elif [[ -f "$ROOT/local.properties" ]] && grep -q '^sdk.dir=' "$ROOT/local.properties"; then
    sed -n 's/^sdk.dir=//p' "$ROOT/local.properties"
  else
    echo "$HOME/Android/Sdk"
  fi
}

SDK="$(sdk_dir)"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"

gradle() {
  "$ROOT/gradlew" -p "$ROOT" "$@"
}

emulator_running() {
  "$ADB" devices | grep -q '^emulator-.*device$'
}

# Starts the AVD unless an emulator is already running, and waits until it has booted.
# Pass --headless to run it without a window.
ensure_emulator() {
  if ! emulator_running; then
    local flags=(-avd "$AVD" -no-audio -no-snapshot-save)
    [[ "${1:-}" == "--headless" ]] && flags+=(-no-window)
    echo "Starting emulator $AVD…"
    nohup "$EMULATOR" "${flags[@]}" >"$ROOT/build/emulator.log" 2>&1 &
  fi
  "$ADB" wait-for-device
  until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    sleep 2
  done
}
