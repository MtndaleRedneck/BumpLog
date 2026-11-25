#!/usr/bin/env bash
# Runs inside the emulator: installs the debug APK and captures real screens with demo data.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
mkdir -p $OUT
adb shell settings put global hide_error_dialogs 1
# Match the primary tester's phone exactly: Galaxy S22 Ultra, 360dp wide at 480dpi,
# font scale 1.00 (measured via a BumpLog feedback report 2026-10-14).
adb shell wm size 1080x2310
adb shell wm density 480
# Verify what actually applied - captures are review evidence and must carry real geometry.
for t in 1 2; do
  SZ=$(adb shell wm size | tr '\n' ' '); DN=$(adb shell wm density | tr '\n' ' '); FS=$(adb shell settings get system font_scale)
  [ "$FS" = "null" ] && FS="1.0 (unset=default)"
  W=$(echo "$SZ" | grep -o '[0-9][0-9]*x[0-9][0-9]*' | tail -1 | cut -dx -f1)
  D=$(echo "$DN" | grep -o '[0-9][0-9]*' | tail -1)
  DPW=$((W*160/D))
  echo "display check: $SZ | $DN | font_scale=$FS -> ${W}px wide @ ${D}dpi = ${DPW}dp"
  [ "$DPW" -ge 355 ] && [ "$DPW" -le 365 ] && break
  if [ "$t" = 2 ]; then echo "DISPLAY GEOMETRY MISMATCH - captures would not match his phone"; exit 1; fi
  adb shell wm size 1080x2310; adb shell wm density 480; sleep 2
done
adb install -r $APK
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT; do adb shell pm grant com.bumplog android.permission.$p; done
shot() { adb shell am force-stop com.bumplog; sleep 1; adb shell am start -n com.bumplog/.MainActivity "${@:2}"; sleep 5; adb exec-out screencap -p > $OUT/$1.png; }
# First launch after install needs longer: it seeds the demo data and warms up. Captured, then discarded.
shot warmup --ez demo true --es screen home
sleep 5
rm -f $OUT/warmup.png
shot home --ez demo true --es screen home
echo "capture check: home.png is $(file -b $OUT/home.png)"
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
shot v16-reported --ez demo true --es screen reported
shot v16-stats --es screen stats
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --ez logstop true
sleep 3; adb exec-out screencap -p > $OUT/v16-logstop-headsup.png
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 3
scroll_to "auto backup" v16-backup
shot v16-askmail --ez demo true --es screen home --ez askmail true
# v1.17: the four approved design changes, captured on the real screens
shot v17-home-gpslost --ez demo true --es screen home --ez mockgpslost true
shot v17-delete-dialog --es screen settings --ez showwipe true
# Restart heads-up: draw Home first, then re-launch warm with the debug extra - the notice
# posts over the drawn screen and the capture beats the ~5s peek window (deterministic).
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home
sleep 7
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --ez logpaused true
sleep 2; adb exec-out screencap -p > $OUT/v17-logpaused-headsup.png
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 3
# v17 backup status line sits at the bottom of the Backup card: scroll until its
# bounds are comfortably above the nav bar, then capture. Coordinates derive from the
# ACTUAL display size (wm size can differ from the requested 1440x2807), never literals.
read DW DH < <(adb shell wm size | grep -o '[0-9][0-9]*x[0-9][0-9]*' | tail -1 | tr 'x' ' ')
SX=$((DW/2)); SY1=$((DH*3/4)); SY2=$((DH/3)); LIM=$((DH*63/100))
for i in $(seq 1 16); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  TOP=$(adb shell cat /sdcard/ui.xml | grep -o 'text="Auto backup: off"[^>]*' | grep -o 'bounds="\[[0-9]*,[0-9]*' | grep -o '[0-9]*$')
  if [ -n "$TOP" ] && [ "$TOP" -gt 100 ] && [ "$TOP" -lt "$LIM" ]; then break; fi
  adb shell input swipe $SX $SY1 $SX $SY2 300; sleep 1
done
sleep 1; adb exec-out screencap -p > $OUT/v17-backup-status.png
# v17 backup failure line: Settings > Backup card as it looks after a failed weekly save.
shot v17-backup-fail-base --es screen settings --ez mockbackupfail true
rm -f $OUT/v17-backup-fail-base.png
scroll_to "Backup failed" v17-backup-failure
# Boundary evidence: the notification-tap action activity must be non-exported.
adb shell dumpsys package com.bumplog | grep -A6 "InternalActionActivity" > $OUT/v17-internal-action-not-exported.txt
cat $OUT/v17-internal-action-not-exported.txt
# Notification-tap lifecycle evidence (Android 12+ trampoline check): tapping the "Logging
# paused" notification must reach InternalActionActivity directly - MainActivity resumes and
# the start flow applies. If a receiver sat in between, the tap would be blocked and nothing
# would resume. (The trip action's mail handoff needs a mail app, which the emulator image
# lacks; the no-mail path is the handOffMail precheck + toast, covered by unit tests.)
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home
sleep 6
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --ez logpaused true
sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell cmd statusbar expand-notifications; sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
B=$(adb shell cat /sdcard/ui.xml | grep -o 'text="Logging paused"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
if [ -n "$B" ]; then
  read X1 Y1 X2 Y2 <<< $(echo "$B" | tr -d '[]' | tr ',' ' ' | awk '{print $1, $2, $3, $4}')
  adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
  sleep 3
else
  echo "WARN: 'Logging paused' notification not found in shade"
fi
{
  echo "-- resumed activity after tapping the notification (MainActivity = the direct-activity path worked) --"
  adb shell dumpsys activity activities | grep -iE "mResumedActivity|topResumedActivity" | head -2
  echo "-- prefs after the tap (detection_enabled true = the start flow consumed restart_wanted) --"
  adb shell run-as com.bumplog cat shared_prefs/spotter.xml 2>/dev/null | grep -oE 'name="(restart_wanted|detection_enabled)"[^/]*' || echo "(run-as unavailable)"
} > $OUT/v17-restart-tap-lifecycle.txt
cat $OUT/v17-restart-tap-lifecycle.txt
# --- Redesign runtime proof: bar styles, feedback row, updates timer, settings scroll rule ---
has_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -q "$1"; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  B=$(adb shell cat /sdcard/ui.xml | grep -o "text=\"[^\"]*$1[^\"]*\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  if [ -z "$B" ]; then echo "FAIL: tap target '$1' not on screen"; exit 1; fi
  read X1 Y1 X2 Y2 <<< $(echo "$B" | tr -d '[]' | tr ',' ' ' | awk '{print $1, $2, $3, $4}')
  adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
  sleep 2
}

# Bottom bar styles (owner-approved trial): same Home screen under all three.
shot v21-bar-pill --ez demo true --es screen home --es barstyle pill
shot v21-bar-glass --ez demo true --es screen home --es barstyle glass
shot v21-bar-flat --ez demo true --es screen home --es barstyle flat

# The feedback row with the more prominent chevron, and the feedback screen itself.
shot v21-settings-top --es screen settings
scroll_to "Send feedback" v21-feedback-row
shot v21-feedback --es screen feedback

# Updates card: the fake up-to-date stub exercises the real result text and 5s hide timer.
# A second identical tap must restart the timer: still visible 3s after it, gone 6s after it.
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --es screen settings --ez fakeuptodate true; sleep 4
scroll_to "Check for updates" v21-updates-card
tap_text "Check for updates"; sleep 1
has_text "up to date" || { echo "FAIL: up-to-date result not shown after tap"; exit 1; }
adb exec-out screencap -p > $OUT/v21-uptodate-shown.png
sleep 3
adb shell am start -n com.bumplog/.MainActivity --ez fakeuptodate true >/dev/null; sleep 1
tap_text "Check for updates"; sleep 1
sleep 3
has_text "up to date" && echo "timer restart OK: identical result still visible 3s after the repeat" || { echo "FAIL: identical result did not restart the 5s timer"; exit 1; }
sleep 3
has_text "up to date" && { echo "FAIL: up-to-date still visible 6s after the repeat tap"; exit 1; } || echo "hide OK: result cleared 5s after the last check"
adb exec-out screencap -p > $OUT/v21-uptodate-hidden.png

# Settings scroll rule: child screens keep the place; leaving Settings entirely resets to the top.
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 4
scroll_to "Send feedback" v21-scroll-before
tap_text "Send feedback"
has_text "What will be sent" || { echo "FAIL: feedback screen did not open"; exit 1; }
adb shell input keyevent 4; sleep 2
has_text "Send feedback" && echo "scroll rule OK: place kept after returning from Feedback" || { echo "FAIL: settings scroll lost after returning from child screen"; exit 1; }
adb exec-out screencap -p > $OUT/v21-scroll-kept.png
tap_text "Home"
tap_text "Settings"
has_text "Send feedback" && { echo "FAIL: settings did not reset to top after leaving entirely"; exit 1; } || echo "scroll rule OK: back at the top after leaving Settings entirely"
has_text "Sensitivity" || { echo "FAIL: settings top not visible after re-entry"; exit 1; }
adb exec-out screencap -p > $OUT/v21-scroll-reset.png

ls -la $OUT
adb shell wm size reset
adb shell wm density reset
