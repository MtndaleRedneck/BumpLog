#!/usr/bin/env bash
# Signed upgrade smoke: install the PUBLIC signed v1.19.0 release, create state through the real UI,
# then adb install -r (no uninstall) the candidate signed APK and check install identity and state.
set -e
OUT=upgrade-smoke; mkdir -p $OUT
OLD=old/BumpLog-v1.19.0.apk; NEW=app/build/outputs/apk/release/app-release.apk
adb shell settings put global hide_error_dialogs 1
adb install $OLD
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS ACTIVITY_RECOGNITION; do adb shell pm grant com.bumplog android.permission.$p || true; done
pkginfo() { adb shell dumpsys package com.bumplog | tr -d '\r' | grep -E "versionCode=|versionName=|firstInstallTime=|lastUpdateTime=" | sed 's/^ *//' | sort -u; }
dump_texts() { adb shell rm -f /sdcard/u.xml; adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml | tr '<' '\n' | grep -o 'text="[^"]*"' | grep -v 'text=""' | sort -u; }
wait_text() { for i in $(seq 1 $2); do dump_texts > $OUT/cur.txt || true; grep -qF "$1" $OUT/cur.txt && return 0; sleep 1; done; return 1; }
tap_text() { adb shell rm -f /sdcard/u.xml; adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  B=$(adb shell cat /sdcard/u.xml | tr '<' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -n "$B" ] || { echo "FAIL: no node $1"; return 1; }
  set -- $B; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
pkginfo > $OUT/old-pkg.txt; cat $OUT/old-pkg.txt
adb shell am start -n com.bumplog/.MainActivity; sleep 6
wait_text "Set up your assistant" 15 || { echo "FAIL: setup not shown on fresh 1.19"; exit 1; }
adb shell am start -n com.bumplog/.MainActivity --es screen home
wait_text "Logging hits" 15 || { echo "FAIL: 1.19 home not shown"; exit 1; }
adb shell dumpsys package com.bumplog | tr -d '\r' | grep -E "android.permission.(ACCESS_|POST_NOTIF)" | grep granted > $OUT/old-perms.txt; cat $OUT/old-perms.txt
dump_texts > $OUT/old-before-tap.txt;
if grep -qF "Allow location and notifications" $OUT/old-before-tap.txt; then echo "FAIL: permission gate still shown on real 1.19 Home before tap"; adb exec-out screencap -p > $OUT/old-gate.png; exit 1; fi
echo "1.19 Home permission gate gone"; adb exec-out screencap -p > $OUT/old-before.png
tap_text "PASSENGER" || tap_text "Passenger"
sleep 3; dump_texts > $OUT/old-after-tap.txt; adb exec-out screencap -p > $OUT/old-after.png
if cmp -s $OUT/old-before-tap.txt $OUT/old-after-tap.txt; then echo "FAIL: tap changed nothing on 1.19 (no state created)"; exit 1; fi
adb shell am force-stop com.bumplog; sleep 2
adb shell am start -n com.bumplog/.MainActivity --es screen home; sleep 6
dump_texts > $OUT/old-relaunch.txt
grep -q "Paused - passenger" $OUT/old-relaunch.txt || { echo "FAIL: state does not persist across relaunch on 1.19 itself; test not discriminating"; cat $OUT/old-relaunch.txt; exit 1; }
grep -qF "Logging hits" $OUT/old-after-tap.txt && grep -c "Logging hits" $OUT/old-after-tap.txt
echo "state created on 1.19 and persists across relaunch"
adb shell am force-stop com.bumplog
adb install -r $NEW
pkginfo > $OUT/new-pkg.txt; cat $OUT/new-pkg.txt
adb logcat -c
adb shell am start -n com.bumplog/.MainActivity --es screen home; sleep 8
dump_texts > $OUT/new-relaunch.txt; adb exec-out screencap -p > $OUT/new-after.png
grep -h firstInstallTime $OUT/old-pkg.txt $OUT/new-pkg.txt
[ "$(grep firstInstallTime $OUT/old-pkg.txt)" = "$(grep firstInstallTime $OUT/new-pkg.txt)" ] || { echo "FAIL: firstInstallTime changed (not an in-place update)"; exit 1; }
grep -q 'versionName=1.20.0' $OUT/new-pkg.txt || { echo "FAIL: versionName not 1.20.0"; exit 1; }
adb logcat -d | grep -q "FATAL EXCEPTION" && { echo "FAIL: crash after upgrade"; adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30; exit 1; }
# Same state after upgrade: compare only the state-bearing driver/passenger texts, not live data.
if cmp -s $OUT/old-after-tap.txt $OUT/new-relaunch.txt; then echo "UPGRADE STATE PRESERVED: Home texts after upgrade equal the post-tap 1.19 texts"; else echo "DIFF between 1.19 post-tap and 1.20.0 texts (expected if layout text changed):"; diff $OUT/old-after-tap.txt $OUT/new-relaunch.txt || true; fi
grep -q "Paused - passenger" $OUT/new-relaunch.txt || { echo "FAIL: 1.20.0 lost the PASSENGER paused state after upgrade"; cat $OUT/new-relaunch.txt; exit 1; }
echo "1.20.0 shows the PASSENGER paused state created on 1.19"
echo "upgrade smoke OK"
