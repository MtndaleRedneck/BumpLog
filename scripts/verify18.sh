#!/usr/bin/env bash
# Emulator check for the update check and the Home screen. Runs on CI only.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/verify; mkdir -p $OUT
P=com.bumplog
grant() { for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT; do adb shell pm grant $P android.permission.$p; done; }
shot() { adb exec-out screencap -p > $OUT/$1.png; }
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml; }
tap_text() { # tap the centre of the first node whose text matches $1
  dump > /tmp/u.xml
  python3 - "$1" <<'PY' > /tmp/xy
import re,sys
t=open('/tmp/u.xml').read()
for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',t):
    if m.group(1)==sys.argv[1]:
        a,b,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b+d)//2); break
PY
  if [ -s /tmp/xy ]; then adb shell input tap $(cat /tmp/xy); echo "tapped $1"; else echo "NOT FOUND $1"; fi
}
reset() { adb shell am force-stop $P; adb shell pm clear $P >/dev/null; grant; }
adb shell settings put global hide_error_dialogs 1
adb install -r $APK
adb shell wm size reset; adb shell wm density 420
reset
adb shell am start -n $P/.MainActivity; sleep 6; adb shell am force-stop $P

# Home, running and stopped
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --ez detoff false --ez mockdrive true --ez mocklive true; sleep 6
shot h-running
adb shell am start -n $P/.MainActivity --es screen home --ez detoff true --ez mockdrive true --ez mocklive false; sleep 4
shot h-stopped
# larger system font
adb shell settings put system font_scale 1.3; adb shell am force-stop $P
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --ez detoff false --ez mockdrive true --ez mocklive false; sleep 6
shot h-bigfont
adb shell settings put system font_scale 1.0

# Settings toggle
reset
adb shell am start -n $P/.MainActivity --ez demo true --es screen settings; sleep 5
for i in 1 2 3 4 5 6 7 8; do dump | grep -q 'text="Check for updates"' && break; adb shell input swipe 540 1700 540 700 300; sleep 1; done
shot s-toggle

# Offer dialog (check run against a pretend older version, so the real latest release counts as newer)
reset
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --es updatecheck 0.0.1; sleep 10
shot u-dialog
dump > $OUT/u-dialog.xml
# Update: download, then the wrong-signature rejection (this debug build is not signed with the release key)
tap_text "Update"; sleep 3; shot u-progress; sleep 40
shot u-failed
adb logcat -d -s AndroidRuntime:E > $OUT/crash-after-update.txt 2>&1
dump | grep -o 'text="[^"]*"' > $OUT/u-failed.txt
ls -la /data/data/$P/cache/updates 2>&1 | head -3; adb shell run-as $P ls cache/updates > $OUT/u-cache-after-fail.txt 2>&1

# Skip this version, then a cold open must stay quiet
reset
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --es updatecheck 0.0.1; sleep 10
tap_text "Skip this version"; sleep 3
adb shell run-as $P cat shared_prefs/$(adb shell run-as $P ls shared_prefs | head -1 | tr -d "\r") > $OUT/prefs-after-skip.txt 2>&1
adb shell am force-stop $P
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --es updatecheck 0.0.1; sleep 10
shot u-after-skip

# Toggle off, then a cold open must stay quiet
reset
adb shell am start -n $P/.MainActivity; sleep 8; adb shell am force-stop $P
adb shell am start -n $P/.MainActivity --ez demo true --es screen settings; sleep 10
for i in 1 2 3 4 5 6 7 8 9 10; do
  dump > /tmp/u3.xml
  YY=$(python3 - <<'PY3'
import re
t=open('/tmp/u3.xml').read()
m=re.search(r'text="Check for updates"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]',t)
print((int(m.group(2))+int(m.group(4)))//2 if m else 0)
PY3
)
  echo "row y=$YY"
  if [ "$YY" -gt 400 ] && [ "$YY" -lt 1400 ]; then break; fi
  adb shell input swipe 540 1500 540 900 400; sleep 2
done
sleep 2; shot s-toggle-before
dump > /tmp/u2.xml
XY=$(python3 - <<'PY2'
import re
t=open('/tmp/u2.xml').read()
m=re.search(r'text="Check for updates"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]',t)
y=(int(m.group(2))+int(m.group(4)))//2 if m else 0
best=None
for n in re.finditer(r'<node[^>]*checkable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]',t):
    a,b,c,d=map(int,n.groups()); cy=(b+d)//2
    if best is None or abs(cy-y)<best[0]: best=(abs(cy-y),(a+c)//2,cy)
print(best[1],best[2]) if best else print(0,0)
PY2
)
echo "switch at $XY"
if [ "$XY" = "0 0" ]; then Y2=$(dump | grep -o "x" | head -0); adb shell input tap $((W*88/100)) 1200; else adb shell input tap $XY; fi; sleep 2; shot s-toggle-off
adb shell run-as $P cat shared_prefs/$(adb shell run-as $P ls shared_prefs | head -1 | tr -d "\r") > $OUT/prefs-after-off.txt 2>&1
adb shell am force-stop $P
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --es updatecheck 0.0.1; sleep 10
shot u-after-off

# Offline: no dialog, no message
reset
adb shell cmd connectivity airplane-mode enable; sleep 4
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --es updatecheck 0.0.1; sleep 12
shot u-offline
adb shell cmd connectivity airplane-mode disable; sleep 3

# Installer hand-off (signature check skipped in this debug-only run, to reach the Android installer)
reset
adb shell am start -n $P/.MainActivity --ez demo true --es screen home --es updatecheck 0.0.1 --ez skipverify true; sleep 10
tap_text "Update"; sleep 45
shot i-settings
adb shell appops set $P REQUEST_INSTALL_PACKAGES allow; sleep 1
adb shell am start -n $P/.MainActivity --ez skipverify true; sleep 8
shot i-installer
dump | grep -o 'text="[^"]*"' > $OUT/i-installer.txt
