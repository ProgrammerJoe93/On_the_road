#!/usr/bin/env bash
# Starts the emulator (AVD otr_test, or $OTR_AVD) and puts it in Helsinki.
#   --headless          run without a window
#   --at <lon> <lat>    put it somewhere else
set -euo pipefail
source "$(dirname "$0")/common.sh"

headless=""
lon=24.94
lat=60.17
while [[ $# -gt 0 ]]; do
  case "$1" in
    --headless) headless=--headless; shift ;;
    --at) lon="$2"; lat="$3"; shift 3 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$ROOT/build"
ensure_emulator $headless
"$ADB" emu geo fix "$lon" "$lat" >/dev/null
echo "Emulator ready at $lat, $lon."
