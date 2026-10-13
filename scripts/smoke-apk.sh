#!/usr/bin/env bash
# Installs an APK on the connected emulator, launches it with location granted,
# and fails if the app crashes or the tracking service doesn't come up.
# Usage: scripts/smoke-apk.sh path/to/app.apk
set -euo pipefail

apk="$1"
pkg=co.shaypunter.worldexplorer

adb install -r "$apk"
adb shell pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$pkg" android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS || true
adb logcat -c

adb shell am start -W -n "$pkg/.MainActivity"
sleep 15

if adb logcat -d -b crash | grep -q "Process: $pkg"; then
  echo "App crashed:"
  adb logcat -d -b crash
  exit 1
fi
if ! adb shell pidof "$pkg" > /dev/null; then
  echo "App is not running"
  adb logcat -d | tail -100
  exit 1
fi
if ! adb shell dumpsys activity services "$pkg" | grep -q LocationTrackingService; then
  echo "Tracking service is not running"
  exit 1
fi
echo "Smoke test passed: $apk"
