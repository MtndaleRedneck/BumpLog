#!/usr/bin/env bash
# Runs inside the emulator: installs the debug APK and captures real screens with demo data.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
adb install -r $APK
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT; do adb shell pm grant com.bumplog android.permission.$p; done
shot() { adb shell am force-stop com.bumplog; sleep 1; adb shell am start -n com.bumplog/.MainActivity "${@:2}"; sleep 5; adb exec-out screencap -p > $OUT/$1.png; }
shot home --ez demo true --es screen home
shot alert --es screen alert --el spot 3
shot review --es screen review
shot setup --ez demo true --es screen setup
shot settings --es screen settings
adb shell input swipe 540 1800 540 400 300; sleep 1; adb exec-out screencap -p > $OUT/settings-2.png
adb shell input swipe 540 1800 540 400 300; sleep 1; adb exec-out screencap -p > $OUT/settings-3.png
for n in 4 5 6 7 8; do adb shell input swipe 540 1800 540 400 300; sleep 1; adb exec-out screencap -p > $OUT/settings-$n.png; done
shot v11-scorecard --es screen score
# scroll_to <text> <out>: swipe until the text is on screen, then nudge it up and capture
scroll_to() {
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb shell cat /sdcard/ui.xml | grep -q "$1"; then break; fi
    adb shell input swipe 540 1800 540 900 300; sleep 1
  done
  adb shell input swipe 540 1500 540 1100 300; sleep 1
  adb exec-out screencap -p > $OUT/$2.png
}
shot v11-voice --es screen settings --es setvoice CUSTOM
scroll_to "My own recordings" v11-voice-recorder
scroll_to "Save to device" v11-backup
shot v11-celebration-base --es screen home
sleep 4
adb shell am start -n com.bumplog/.MainActivity --es screen home --ez celebrate true; sleep 2; adb exec-out screencap -p > $OUT/v11-celebration.png
sleep 1; adb exec-out screencap -p > $OUT/v11-celebration-2.png
rm -f $OUT/v11-celebration-base.png
shot v11-confirm-fixed --es screen markfixed --el spot 1
shot spot --es screen spot --el spot 1
shot damage --es screen damage
shot v12-home-off --ez demo true --es screen home --ez detoff true
shot v12-home-on --es screen home --ez detoff false
shot v12-scorecard --es screen score
shot v12-settings --es screen settings
ls -la $OUT
