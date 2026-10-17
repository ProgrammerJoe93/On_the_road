#!/usr/bin/env bash
# Builds the debug APK. Extra arguments go to Gradle, e.g. --offline.
set -euo pipefail
source "$(dirname "$0")/common.sh"

gradle :app:assembleDebug "$@"
echo "APK: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
