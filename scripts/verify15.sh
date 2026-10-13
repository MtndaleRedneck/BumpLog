#!/usr/bin/env bash
# Emulator checks for recording lifecycle: leaving the screen, backgrounding, and the 8 second limit.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/verify
mkdir -p $OUT
LOG=$OUT/log.txt
: > $LOG
adb shell settings put global hide_error_dialogs 1
adb install -r $APK
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT RECORD_AUDIO; do adb shell pm grant com.bumplog android.permission.$p; done
adb shell wm size reset; adb shell wm density 420; adb shell settings put system font_scale 1.0
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  XY=$(python3 - "$1" <<'PY'
import sys,re
import xml.etree.ElementTree as E
want=sys.argv[1]
for n in E.parse('/tmp/ui.xml').iter('node'):
    if n.get('text')==want:
        a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
        print((a+c)//2,(b+d)//2); break
PY
)
  echo "tap $1 at $XY"
  [ -n "$XY" ] && adb shell input tap $XY
}
count_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"" | wc -l; }
files() { adb shell run-as com.bumplog find files cache -type f 2>/dev/null | sort; }
mic() { echo "--- mic state ($1)" | tee -a $LOG; adb shell dumpsys appops --package com.bumplog 2>/dev/null | grep -i -A2 RECORD_AUDIO | tee -a $LOG; adb shell dumpsys audio 2>/dev/null | grep -i -B1 -A3 "com.bumplog" | grep -i -E "rec|source|state" | head -8 | tee -a $LOG; }
launch() { adb shell am force-stop com.bumplog; adb shell am start -n com.bumplog/.MainActivity --es screen recordings --ez seedclips true; sleep 6; }


texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\+"' | tr '\n' '|'; }
has() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -c "text=\"$1\""; }
shot() { adb exec-out screencap -p > $OUT/$1.png; }
scroll_to() { for i in $(seq 1 14); do [ "$(has "$1")" -gt 0 ] && return 0; adb shell input swipe 540 1800 540 900 250; sleep 1; done; return 1; }
adb shell am force-stop com.bumplog
adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 6
scroll_to "Copy setup prompt"; echo "found setup card: $?" | tee -a $LOG
shot a1-setup-card
scroll_to "General feedback, bugs & feature suggestions"; echo "found feedback row: $?" | tee -a $LOG
shot a2-before-feedback
BEFORE=$(texts); echo "BEFORE: $BEFORE" | tee -a $LOG
tap_text "General feedback, bugs & feature suggestions"; sleep 2
shot a3-feedback-screen
echo "feedback screen texts: $(texts)" | tee -a $LOG
adb shell input keyevent 4; sleep 2
shot a4-after-feedback
AFTER=$(texts); echo "AFTER: $AFTER" | tee -a $LOG
[ "$BEFORE" = "$AFTER" ] && echo "RESULT feedback scroll restored: SAME" | tee -a $LOG || echo "RESULT feedback scroll restored: DIFFERENT" | tee -a $LOG
# Voice
adb shell input swipe 540 900 540 1800 250; adb shell input swipe 540 900 540 1800 250; sleep 1
scroll_to "Phone default"; echo "found voice row: $?" | tee -a $LOG
BEFORE=$(texts); echo "BEFORE: $BEFORE" | tee -a $LOG
shot b1-before-voice
tap_text "Phone default"; sleep 2
shot b2-voice-screen
echo "voice screen: $(texts)" | tee -a $LOG
tap_text "Done"; sleep 2
shot b3-after-voice
AFTER=$(texts); echo "AFTER: $AFTER" | tee -a $LOG
[ "$BEFORE" = "$AFTER" ] && echo "RESULT voice scroll restored: SAME" | tee -a $LOG || echo "RESULT voice scroll restored: DIFFERENT" | tee -a $LOG
ls -la $OUT
