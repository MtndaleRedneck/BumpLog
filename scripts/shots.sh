#!/usr/bin/env bash
# Runs inside the emulator: installs the debug APK and captures real screens with demo data.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
mkdir -p $OUT
adb install -r $APK
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT; do adb shell pm grant com.bumplog android.permission.$p; done
shot() { adb shell am force-stop com.bumplog; sleep 1; adb shell am start -n com.bumplog/.MainActivity "${@:2}"; sleep 5; adb exec-out screencap -p > $OUT/$1.png; }
shot home --ez demo true --es screen home
shot alert --es screen alert --el spot 3
shot review --es screen review
shot settings --es screen settings
adb shell input swipe 540 1800 540 400 300; sleep 1; adb exec-out screencap -p > $OUT/settings-2.png
adb shell input swipe 540 1800 540 400 300; sleep 1; adb exec-out screencap -p > $OUT/settings-3.png
shot spot --es screen spot --el spot 1
shot damage --es screen damage
ls -la $OUT
