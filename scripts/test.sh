#!/usr/bin/env bash
# Runs the JVM unit tests. Extra arguments go to Gradle, e.g. --offline or --tests '*Parser*'.
#   --live   also run the tests that call the real Valhalla and Overpass servers
set -euo pipefail
source "$(dirname "$0")/common.sh"

args=()
for arg in "$@"; do
  if [[ "$arg" == --live ]]; then
    export OTR_LIVE_TESTS=1
  else
    args+=("$arg")
  fi
done

gradle :app:testDebugUnitTest "${args[@]}" ||
  { echo "Report: $ROOT/app/build/reports/tests/testDebugUnitTest/index.html"; exit 1; }
