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
shot() { adb exec-out screencap -p > $OUT/$1.png; }
home_label() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="HITS [A-Z ]*"' | head -1; }
drive_id() { adb shell run-as com.bumplog cat shared_prefs/spotter.xml 2>/dev/null | grep -o 'name="last_drive_id" value="[0-9]*"' | grep -o '[0-9]*"$' | tr -d '"'; }
addhits() { for i in $(seq 1 $2); do adb shell "run-as com.bumplog sqlite3 databases/spotter.db \"INSERT INTO hits(spot_id,drive_id,ts,lat,lon,heading,speed,peak_g,duration,peaks,type) VALUES (1,$1,$(date +%s)000,40.0,-74.0,90,30,1.0,50,1,'POTHOLE')\"" ; done; }
tap_s() { for k in 1 2 3 4 5; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; if adb shell cat /sdcard/ui.xml | grep -q "text=\"$1\""; then tap_text "$1"; return 0; fi; adb shell input swipe 540 1800 540 700 250; sleep 1; done; echo "tap_s: $1 not found" | tee -a $LOG; }
tab_cycle() { tap_text Settings; sleep 1; tap_text Home; sleep 2; }
echo "sqlite3 on device: $(adb shell 'which sqlite3; ls /system/bin/sqlite3' 2>&1 | head -2)" | tee -a $LOG
adb shell am force-stop com.bumplog
adb shell pm clear com.bumplog >/dev/null
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT RECORD_AUDIO; do adb shell pm grant com.bumplog android.permission.$p; done
adb shell am start -n com.bumplog/.MainActivity; sleep 6
tap_text "Skip for now"; sleep 3
echo "S0 no drives yet: $(home_label) | $(texts | cut -c1-300)" | tee -a $LOG
shot c0-no-drives
tap_s Start; sleep 4
D1=$(drive_id); echo "S1 drive1 id=$D1" | tee -a $LOG
tab_cycle
echo "S1 running, 0 hits: $(home_label)" | tee -a $LOG; shot c1-running-zero
addhits $D1 3; tab_cycle
echo "S2 running, 3 hits inserted: $(home_label) | $(texts | grep -o 'HITS[^|]*|[0-9]*' | head -1)" | tee -a $LOG; shot c2-running-three
tap_s Stop; sleep 4; tab_cycle
echo "S3 parked after drive1: $(home_label) | $(texts | grep -o 'HITS[^|]*|[0-9]*' | head -1)" | tee -a $LOG; shot c3-parked-three
tap_s Start; sleep 4
D2=$(drive_id); echo "S4 drive2 id=$D2 (differs from $D1: $([ "$D1" != "$D2" ] && echo yes || echo NO))" | tee -a $LOG
tab_cycle
echo "S4 new drive running: $(home_label) | $(texts | grep -o 'HITS[^|]*|[0-9]*' | head -1)" | tee -a $LOG; shot c4-new-drive-zero
addhits $D2 1; tab_cycle
echo "S5 drive2 one hit: $(home_label) | $(texts | grep -o 'HITS[^|]*|[0-9]*' | head -1)" | tee -a $LOG; shot c5-new-drive-one
tap_s Stop; sleep 4; tab_cycle
echo "S6 parked after drive2: $(home_label) | $(texts | grep -o 'HITS[^|]*|[0-9]*' | head -1)" | tee -a $LOG; shot c6-parked-one
adb shell am force-stop com.bumplog; adb shell am start -n com.bumplog/.MainActivity; sleep 5
echo "S7 after app restart, parked: $(home_label) | $(texts | grep -o 'HITS[^|]*|[0-9]*' | head -1)" | tee -a $LOG; shot c7-after-restart
ls -la $OUT
exit 0
