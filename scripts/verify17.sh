#!/usr/bin/env bash
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/verify; mkdir -p $OUT; LOG=$OUT/log.txt; : > $LOG
adb shell settings put global hide_error_dialogs 1
adb install -r $APK
adb shell wm size reset; adb shell wm density 420; adb shell settings put system font_scale 1.0
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  XY=$(python3 - "$1" <<'PY'
import sys,re
import xml.etree.ElementTree as E
for n in E.parse('/tmp/ui.xml').iter('node'):
    if n.get('text')==sys.argv[1]:
        a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds'))); print((a+c)//2,(b+d)//2); break
PY
)
  echo "tap $1 at $XY" | tee -a $LOG; [ -n "$XY" ] && adb shell input tap $XY
}
shot() { adb exec-out screencap -p > $OUT/$1.png; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\+"' | tr '\n' '|'; }
adb shell pm clear com.bumplog >/dev/null
adb shell am start -n com.bumplog/.MainActivity; sleep 6
tap_text "Skip for now"; sleep 3
adb shell am force-stop com.bumplog; adb shell am start -n com.bumplog/.MainActivity --es screen voice; sleep 6
echo "voice screen: $(texts)" | tee -a $LOG; shot v0-voice
adb logcat -c
for v in Adam Michael Default Bella Sarah Sky; do tap_text $v; sleep 3; done
shot v1-sky-selected
adb logcat -d | grep "natural clip" | tee -a $LOG
echo "RESULT natural ok count: $(grep -c 'natural clip played' $LOG) failed: $(grep -c 'natural clip failed' $LOG)" | tee -a $LOG
adb shell am force-stop com.bumplog; adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 6
for k in 1 2 3 4 5 6; do if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml | grep -q 'text="Voice"'; then break; fi; adb shell input swipe 540 1800 540 700 250; sleep 1; done
shot v2-settings
echo "settings texts: $(texts | cut -c1-900)" | tee -a $LOG
adb shell input swipe 540 1200 540 1800 250; sleep 1; adb shell input swipe 540 1200 540 1800 250; sleep 1
for k in 1 2 3 4 5 6 7 8; do if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml | grep -q 'text="Sensitivity"'; then break; fi; adb shell input swipe 540 1800 540 900 250; sleep 1; done
shot v3-sensitivity
echo "sensitivity texts: $(texts | cut -c1-900)" | tee -a $LOG
