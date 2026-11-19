#!/usr/bin/env bash
# Builds, tests and installs the debug app on the connected phone, then waits for the pill to reconnect.
#
# Never force-stop the app (am force-stop, "Force stop" in App info): Android then removes it from the
# enabled accessibility services and the pill stays off until it is re-enabled by hand.
set -euo pipefail

cd "$(dirname "$0")/.."

# The Android Gradle plugin needs JDK 17 or newer; default to Homebrew's JDK 21 when none is configured.
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21}"
export JAVA_HOME

./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "Waiting for the accessibility service to reconnect..."
for _ in $(seq 1 10); do
  if adb shell dumpsys accessibility | grep -q 'Service\[label=Dynamic Pill'; then
    echo "Connected."
    exit 0
  fi
  sleep 3
done

echo "Not connected yet. Open the app: its health checklist shows what to fix." >&2
exit 1
