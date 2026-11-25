#!/usr/bin/env bash
# Release (minified + resource-shrunk) runtime smoke: install the signed release APK on the
# emulator, launch the key screens, and fail on any crash. This is the release-build evidence
# the debug-only jobs cannot give.
set -e
APK=app/build/outputs/apk/release/app-release.apk
OUT=release-smoke
mkdir -p $OUT
[ -f "$APK" ] || { echo "FAIL: $APK missing"; exit 1; }
adb shell settings put global hide_error_dialogs 1
adb install -r $APK
adb shell am force-stop com.bumplog; sleep 1
adb logcat -c
adb shell am start -n com.bumplog/.MainActivity; sleep 6
PID=$(adb shell pidof com.bumplog | tr -d '\r')
if [ -z "$PID" ]; then echo "FAIL: release app not running after launch"; adb logcat -d | tail -80; exit 1; fi
adb exec-out screencap -p > $OUT/release-home.png
# Settings and its child feedback screen exercise the minified Compose graph and resources.
adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 3
adb exec-out screencap -p > $OUT/release-settings.png
adb shell am start -n com.bumplog/.MainActivity --es screen feedback; sleep 3
adb exec-out screencap -p > $OUT/release-feedback.png
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "FAIL: FATAL EXCEPTION in logcat"
  adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -40
  exit 1
fi
PID=$(adb shell pidof com.bumplog | tr -d '\r')
[ -z "$PID" ] && { echo "FAIL: app died while exercising screens"; exit 1; }
echo "release smoke OK (pid $PID)"
ls -la $OUT
