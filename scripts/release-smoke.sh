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
# dump_ui: fresh UI hierarchy into $UI_XML, fail-closed: a failed dump exits the script, so an
# assertion can never read a stale or missing /sdcard/ui.xml. Called directly so exit really exits.
dump_ui() {
  adb shell rm -f /sdcard/ui.xml
  if ! adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; then echo "FAIL: uiautomator dump failed"; exit 1; fi
  UI_XML=$(adb shell cat /sdcard/ui.xml) || { echo "FAIL: could not read the UI dump"; exit 1; }
  # A successful dump with an empty or invalid read must never pass an absence assertion:
  # require a non-empty hierarchy document before any grep sees it.
  case "$UI_XML" in *"<hierarchy"*) ;; *) echo "FAIL: UI dump has no hierarchy root"; exit 1 ;; esac
}
# has_text <fixed string>: 0 present, 1 absent; a failed dump exits before the grep.
has_text() { dump_ui; printf '%s\n' "$UI_XML" | grep -qF "$1"; }
# wait_text <fixed> <timeout-s>: poll until the text renders (bounded), so slow first-frame or
# warm-route timing can never false-red a working screen.
wait_text() {
  DEADLINE=$(( $(date +%s) + $2 ))
  while [ $(date +%s) -lt $DEADLINE ]; do
    dump_ui
    if printf '%s\n' "$UI_XML" | grep -qF "$1"; then return 0; fi
    sleep 1
  done
  return 1
}
# fail <name> <message>: capture what IS on screen (pixels, visible texts, any crash) before dying,
# so the next failure is diagnosable from the artifact alone.
fail() {
  echo "FAIL: $2"
  adb exec-out screencap -p > $OUT/fail-$1.png 2>/dev/null
  adb shell uiautomator dump /sdcard/ui-fail.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui-fail.xml | tr '<' '\n' | grep -o 'text="[^"]*"' | head -20 | tee $OUT/fail-$1-texts.txt
  adb logcat -d | grep -A25 'FATAL EXCEPTION' | head -40
  exit 1
}
adb shell am force-stop com.bumplog; sleep 1
adb logcat -c
adb shell am start -n com.bumplog/.MainActivity; sleep 6
PID=$(adb shell pidof com.bumplog | tr -d '\r')
if [ -z "$PID" ]; then echo "FAIL: release app not running after launch"; adb logcat -d | tail -80; exit 1; fi
# Real screen assertions, not just process liveness: each key screen must render its own content.
# A fresh install truthfully lands on the assistant setup screen (setupDone is false).
wait_text "Set up your assistant" 12 || fail setup "setup screen not rendered on fresh install"
adb exec-out screencap -p > $OUT/release-setup.png
# Warm route to Home exercises the minified home graph (singleTask: onNewIntent handles the extra).
adb shell am start -n com.bumplog/.MainActivity --es screen home
wait_text "Detection" 12 || fail home "home screen content not rendered after warm route"
adb exec-out screencap -p > $OUT/release-home.png
# Settings and its child feedback screen exercise the minified Compose graph and resources.
adb shell am start -n com.bumplog/.MainActivity --es screen settings
wait_text "Sensitivity" 12 || fail settings "settings screen content not rendered"
adb exec-out screencap -p > $OUT/release-settings.png
adb shell am start -n com.bumplog/.MainActivity --es screen feedback
wait_text "What will be sent" 12 || fail feedback "feedback screen content not rendered"
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
