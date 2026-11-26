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
# Install must propagate: a failed install on a reused emulator leaves the OLD app answering
# every later assertion. Verify presence AND the exact version the APK carries.
adb install -r $APK || { echo "FAIL: adb install failed"; exit 1; }
adb shell pm path com.bumplog >/dev/null || { echo "FAIL: com.bumplog absent after install"; exit 1; }
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)
[ -n "$AAPT" ] || { echo "FAIL: no aapt found under $ANDROID_HOME/build-tools"; exit 1; }
WANT_V=$("$AAPT" dump badging "$APK" 2>/dev/null | grep -oP "versionName='\K[^']+")
GOT_V=$(adb shell dumpsys package com.bumplog | grep -m1 -oP 'versionName=\K\S+')
[ -n "$WANT_V" ] || { echo "FAIL: could not read versionName from $APK"; exit 1; }
[ "$WANT_V" = "$GOT_V" ] || { echo "FAIL: installed version '$GOT_V' != APK version '$WANT_V' (stale app survived install)"; exit 1; }
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT; do adb shell pm grant com.bumplog android.permission.$p || { echo "FAIL: pm grant $p"; exit 1; }; done
# statusbar collapse first: the notification-tap section leaves the shade open on its WARN path,
# and a shade left open poisons every later capture AND every uiautomator dump (it reads the shade).
shot() { adb shell cmd statusbar collapse; adb shell am force-stop com.bumplog; sleep 1; adb shell am start -n com.bumplog/.MainActivity "${@:2}"; sleep 5; adb exec-out screencap -p > $OUT/$1.png; }
# dump_ui: fresh UI hierarchy into $UI_XML, fail-closed: a failed dump exits the script, so no
# assertion, scroll or tap can ever read a stale or missing /sdcard/ui.xml. Always called
# directly (never in a subshell or pipeline) so the exit really exits.
# dump_ui_soft: one dump attempt. 0 = fresh hierarchy in $UI_XML; 1 = no fresh dump. It clears
# $UI_XML first, so a failed attempt can never leave a stale document for a grep to read.
dump_ui_soft() {
  # One attempt, nonfatal - but the same enforced freshness as dump_ui: verified delete,
  # successful dump, fresh complete-hierarchy read. Any failed step returns 1 WITHOUT
  # touching UI_XML, so a stale hierarchy can never turn into a positive found/absent read.
  UI_XML=""
  adb shell rm -f /sdcard/ui.xml 2>/dev/null || return 1
  adb shell test ! -e /sdcard/ui.xml || return 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local X
  X=$(adb shell cat /sdcard/ui.xml 2>/dev/null) || return 1
  case "$X" in *"<hierarchy"*"</hierarchy>"*) UI_XML="$X"; return 0 ;; *) return 1 ;; esac
}
dump_ui() {
  # Bounded retry around a transiently unreadable dump. Freshness is enforced, not assumed:
  # every attempt REQUIRES a verified delete of the old file, a successful dump, and a fresh
  # read carrying a hierarchy root. A failed delete or dump invalidates the attempt before any
  # read happens, so a stale or partial hierarchy can never pass. Fail-closed with diagnostics.
  local i OUT
  for i in 1 2 3; do
    UI_XML=""
    OUT=""
    adb shell rm -f /sdcard/ui.xml 2>/dev/null || { echo "WARN: UI dump attempt $i: could not clear /sdcard/ui.xml; retrying"; sleep 2; continue; }
    adb shell test ! -e /sdcard/ui.xml || { echo "WARN: UI dump attempt $i: old dump survived the delete; retrying"; sleep 2; continue; }
    OUT=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1) || { echo "WARN: UI dump attempt $i: dump failed ('${OUT:-no output}'); retrying"; sleep 2; continue; }
    UI_XML=$(adb shell cat /sdcard/ui.xml 2>/dev/null) || { echo "WARN: UI dump attempt $i: dump file unreadable (dump said: '${OUT:-no output}'); retrying"; sleep 2; continue; }
    case "$UI_XML" in
      *"<hierarchy"*"</hierarchy>"*) return 0 ;;
      *) echo "WARN: UI dump attempt $i: incomplete hierarchy (missing root or closing tag); retrying"; sleep 2 ;;
    esac
  done
  echo "FAIL: could not read the UI dump after 3 attempts (last dump said: '${OUT:-no output}')"
  adb shell ls -la /sdcard/ui.xml 2>&1 || true
  exit 1
}
# has_text <fixed string>: 0 present, 1 absent; a failed dump exits before the grep, so a
# negative assertion can never read a failed dump as absence.
has_text() { dump_ui; printf '%s\n' "$UI_XML" | grep -qF "$1"; }
# tap_now <text>: tap the node's center with no settle sleep, for measured-clock probes.
# Fixed-string exact text match first, fixed-string substring fallback; missing target fails closed.
tap_now() {
  dump_ui
  LINE=$(printf '%s\n' "$UI_XML" | tr '<' '\n' | grep -F "text=\"$1\"" | head -1)
  if [ -z "$LINE" ]; then LINE=$(printf '%s\n' "$UI_XML" | tr '<' '\n' | grep -F 'text="' | grep -F "$1" | head -1); fi
  # Anchor to the bounds= attribute: a bare bracket pattern can match literal "[12,34][56,78]" inside text.
  B=$(printf '%s\n' "$LINE" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | sed 's/^bounds="//; s/"$//')
  if [ -z "$B" ]; then echo "FAIL: tap target '$1' not on screen"; exit 1; fi
  # Numbers-only extraction: string edits on the bracket form can silently join coordinates.
  read X1 Y1 X2 Y2 <<< $(echo "$B" | grep -o '[0-9]\+' | tr '\n' ' ')
  # Validate the BOX, not just the midpoint: 4 numeric values, ordered, inside the display.
  # (Real evidence: joining "1794][751" once produced a valid-looking midpoint from garbage.)
  if [ -z "$X1" ] || [ -z "$Y1" ] || [ -z "$X2" ] || [ -z "$Y2" ]      || [ "$X1" -ge "$X2" ] || [ "$Y1" -ge "$Y2" ]      || [ "$X1" -lt 0 ] || [ "$X2" -gt 1080 ] || [ "$Y1" -lt 0 ] || [ "$Y2" -gt 2310 ]; then
    echo "FAIL: invalid bounds $B for '$1'"; exit 1
  fi
  TX=$(( (X1+X2)/2 )); TY=$(( (Y1+Y2)/2 ))
  adb shell input tap $TX $TY
  LAST_TAP_X=$TX; LAST_TAP_Y=$TY
}
# retap_last: re-tap the exact point tap_now just verified, with NO re-dump. Only for an
# immediate second tap on the same static screen (the timer restart probe), where a fresh
# dump's ~2.4s latency would push the second tap past the 5s window it must land inside.
retap_last() {
  if [ -z "${LAST_TAP_X:-}" ]; then echo "FAIL: retap_last with no prior tap_now"; exit 1; fi
  adb shell input tap $LAST_TAP_X $LAST_TAP_Y
}
# tap_text <text>: tap, then settle 2s.
tap_text() { tap_now "$1"; sleep 2; }
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
# scroll_to <text> <out>: swipe until the text is on screen, then nudge it up and capture.
# 20 swipes covers the full settings list (the Feedback/Updates cards sit below the tall Testing
# card, past 12); fail-closed when the text never appears so a miss can never capture silently.
scroll_to() {
  FOUND=""
  for i in $(seq 1 20); do
    dump_ui
    if printf '%s\n' "$UI_XML" | grep -qF "$1"; then FOUND=1; break; fi
    adb shell input swipe 540 1800 540 900 300; sleep 1
  done
  if [ -z "$FOUND" ]; then echo "FAIL: '$1' never appeared after 20 swipes"; exit 1; fi
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
# Installed-icon pixel: with the "Logging paused" notification just posted, this capture's
# status-bar strip and heads-up banner show the new white logo as installed - the artifact for
# the clipping/recognizability check.
adb exec-out screencap -p > $OUT/v21-notif-icon-statusbar.png
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --es screen settings; sleep 3
# v17 backup status line sits at the bottom of the Backup card: scroll until its
# bounds are comfortably above the nav bar, then capture. Coordinates derive from the
# ACTUAL display size (wm size can differ from the requested 1440x2807), never literals.
read DW DH < <(adb shell wm size | grep -o '[0-9][0-9]*x[0-9][0-9]*' | tail -1 | tr 'x' ' ')
SX=$((DW/2)); SY1=$((DH*3/4)); SY2=$((DH/3)); LIM=$((DH*63/100))
for i in $(seq 1 16); do
  dump_ui
  TOP=$(printf '%s\n' "$UI_XML" | tr '<' '\n' | grep -F 'text="Auto backup: off"' | grep -o 'bounds="\[[0-9]*,[0-9]*' | grep -o '[0-9]*$')
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
dump_ui
# Discriminator, captured BEFORE the tap (the record can disappear after it): the posted
# "Logging paused" notification's tap must route through the non-exported
# InternalActionActivity (do=restart), which UNCONDITIONALLY sets restart_wanted=true.
# Only with tramp-ok here does a post-tap restart_wanted=false prove MainActivity consumed it.
adb shell dumpsys notification > $OUT/v17-dumpsys-notification.txt
TRAMP=$(awk 'BEGIN{RS="NotificationRecord\\("} /Logging paused/ { if ($0 ~ /InternalActionActivity/) print "tramp-ok"; else print "tramp-missing"; found=1; exit } END { if (!found) print "tramp-no-record" }' $OUT/v17-dumpsys-notification.txt)
B=$(printf '%s\n' "$UI_XML" | tr '<' '\n' | grep -F 'text="Logging paused"' | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | sed 's/^bounds="//; s/"$//')
if [ -n "$B" ]; then
  read X1 Y1 X2 Y2 <<< $(echo "$B" | grep -o '[0-9]\+' | tr '\n' ' ')
  if [ -z "$X1" ] || [ -z "$Y1" ] || [ -z "$X2" ] || [ -z "$Y2" ]      || [ "$X1" -ge "$X2" ] || [ "$Y1" -ge "$Y2" ]      || [ "$X1" -lt 0 ] || [ "$X2" -gt 1080 ] || [ "$Y1" -lt 0 ] || [ "$Y2" -gt 2310 ]; then
    echo "FAIL: invalid notification bounds $B"; exit 1
  fi
  TX=$(( (X1+X2)/2 )); TY=$(( (Y1+Y2)/2 ))
  adb shell input tap $TX $TY
  sleep 3
else
  echo "WARN: 'Logging paused' notification not found in shade"
fi
{
  echo "-- notification tap route ($TRAMP: tramp-ok = the tap goes through the non-exported trampoline that sets restart_wanted) --"
  echo "-- resumed activity after tapping the notification (MainActivity = the direct-activity path worked) --"
  RESUMED=$(adb shell dumpsys activity activities | grep -iE "mResumedActivity|topResumedActivity" | head -2)
  printf '%s\n' "$RESUMED"
  echo "-- prefs after the tap + consumption verdict (restart_wanted=false proves consumption ONLY if the pre-tap route was tramp-ok; otherwise the default is also false and nothing is distinguished) --"
  # The fixture boundary: only the two synthetic keys may leave the device. set +x around the
  # read so xtrace can never echo the full prefs file into the CI log via the assignment trace.
  set +x
  RA=$(adb shell run-as com.bumplog cat shared_prefs/spotter.xml 2>&1); RC=$?
  # Qualify the outcome: command failure, read-OK-but-keys-absent, and keys-present are three
  # different findings and must never be reported as each other.
  if [ $RC -ne 0 ]; then PREFS_OUT="(prefs read FAILED rc=$RC: $(printf '%s\n' "$RA" | head -1))";
  else
    KEYS=$(printf '%s\n' "$RA" | grep -oE 'name="(restart_wanted|detection)"[^/]*')
    if [ -n "$KEYS" ]; then PREFS_OUT="$KEYS"; else PREFS_OUT="(prefs read OK; restart_wanted/detection keys absent)"; fi
  fi
  set -x
  printf '%s\n' "$PREFS_OUT"
  # Bounded verdict: consumption is PROVEN only when all three hold - the pre-tap dump showed
  # the tap routes via the trampoline (tramp-ok), MainActivity resumed, and restart_wanted
  # now reads false. Anything else keeps the known uncovered qualification, never a pass claim.
  if [ "$TRAMP" = "tramp-ok" ] && printf '%s\n' "$RESUMED" | grep -q 'com.bumplog/.MainActivity' && printf '%s\n' "$PREFS_OUT" | grep -q 'name="restart_wanted"[^/]*false'; then
    echo "restart-consumption: PROVEN (pre-tap tramp-ok + MainActivity resumed + post-tap restart_wanted=false)"
  else
    echo "restart-consumption: NOT-DISTINGUISHED (route=$TRAMP; resumed and prefs above) - qualification stands"
  fi
} > $OUT/v17-restart-tap-lifecycle.txt
cat $OUT/v17-restart-tap-lifecycle.txt
# Never let the shade leak into the redesign captures below, whatever the tap outcome was.
adb shell cmd statusbar collapse
sleep 1
# --- Redesign runtime proof: bar styles, feedback row, updates timer, settings scroll rule ---
# 1.19: Home with the app-icon mark on Report last hit, both looks. The old triangle is gone.
shot v22-home-mono --ez demo true --es screen home --es reporticon mono
shot v22-home-color --ez demo true --es screen home --es reporticon color
shot v22-home-rest --ez demo true --es screen home --es reporticon mono

# Press feel: hold a finger on Report last hit and capture mid-press. The button must be visibly smaller and
# dimmer than in v22-home-rest. The vibrator service log before and after shows whether the tick fired.
hold_capture() {
  # hold_capture <text> <out> : press-and-hold the node centre for 3s, capture at ~1.2s.
  dump_ui
  LINE=$(printf '%s\n' "$UI_XML" | tr '<' '\n' | grep -F 'text="' | grep -F "$1" | head -1)
  B=$(printf '%s\n' "$LINE" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  read X1 Y1 X2 Y2 <<< $(echo "$B" | grep -o '[0-9]\+' | tr '\n' ' ')
  if [ -z "$X1" ] || [ -z "$Y2" ]; then echo "FAIL: hold target '$1' not on screen"; exit 1; fi
  HX=$(( (X1+X2)/2 )); HY=$(( (Y1+Y2)/2 - 30 ))
  adb shell dumpsys vibrator_manager > $OUT/$2-vibrator-before.txt 2>&1 || true
  adb shell input swipe $HX $HY $HX $HY 3000 &
  sleep 1.2
  adb exec-out screencap -p > $OUT/$2.png
  wait
  adb shell dumpsys vibrator_manager > $OUT/$2-vibrator-after.txt 2>&1 || true
}
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --es reporticon mono; sleep 5
hold_capture "Report last hit" v22-press-report
# Same hold on a bottom tab.
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --es reporticon mono; sleep 5
hold_capture "Stats" v22-press-tab
# Vibrate on press switch and the Report icon setting, plus the colour picker without the bright orange.
shot v22-settings-appearance --es screen settings
has_text "Report icon" || { echo "FAIL: Report icon setting missing"; exit 1; }
has_text "Vibrate on press" || { echo "FAIL: Vibrate on press switch missing"; exit 1; }
has_text "Monochrome" || { echo "FAIL: Monochrome button missing"; exit 1; }
has_text "Full color" || { echo "FAIL: Full color button missing"; exit 1; }
if has_text "Bottom bar"; then echo "FAIL: the Bottom bar chooser should be gone (Compact pill is permanent)"; exit 1; fi

# The feedback row with the more prominent chevron, and the feedback screen itself.
shot v21-settings-top --es screen settings
scroll_to "Send feedback" v21-feedback-row
shot v21-feedback --es screen feedback

# Updates card: the fake up-to-date stub exercises the real result text and 5s hide timer.
# Measured-clock probe: the second tap must land BEFORE the first 5s deadline (that is what makes
# it a restart check), the visible assertion lands between the two deadlines, and the hidden
# assertion lands after the second deadline with a 2s margin for uiautomator dump latency.
# If the emulator is so slow the second tap misses the window, the restart claim is skipped with a
# WARN (never a false red) and the repeat still verifies shown-then-hidden against its own deadline.
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --es screen settings --ez fakeuptodate true; sleep 4
scroll_to "Check for updates" v21-updates-card
tap_now "Check for updates"; T1=$(date +%s%3N)
FOUND=0
for i in $(seq 1 8); do dump_ui_soft && printf '%s\n' "$UI_XML" | grep -qF "up to date" && { FOUND=1; break; }; sleep 1; done
[ "$FOUND" = 1 ] || { echo "FAIL: up-to-date result not shown after tap"; exit 1; }
adb exec-out screencap -p > $OUT/v21-uptodate-shown.png
# Second tap via a REAL re-find (tap_now re-dumps and re-derives the bounds): no cached-point
# geometry assumption. Both taps take the deterministic fake path (fakeuptodate arms a count of
# 2), so no network latency, toast or offer-popup branch can shift what the taps do. Clocks are
# millisecond. SCOPE: these pixels/assertions prove the result APPEARS and HIDES on its 5s timer.
# The repeat-tap window below is OBSERVATIONAL evidence about restart behavior, qualified by
# emulator tap->result latency and dump duration; it is not a controlled virtual-clock proof.
tap_now "Check for updates"; T2=$(date +%s%3N)
GAP_MS=$((T2 - T1)); echo "timer clocks (ms): T1=$T1 T2=$T2 gap=${GAP_MS}ms"
if [ "$GAP_MS" -ge 5000 ]; then echo "WARN: repeat tap landed ${GAP_MS}ms after the first; restart window missed, checking fresh-timer behavior only"; fi
# Restart observation, banded by the ACTUAL observation clock:
#   obs <  T2+4000ms            -> clean window: visible = qualified restart evidence, absent = FAIL
#   T2+4000 <= obs < T2+5000    -> last-1s jitter band: WARN either way (dump latency can straddle the deadline)
#   obs >= T2+5000              -> past the deadline: WARN INCONCLUSIVE
TARGET=$((T1 + 6000)); NOW=$(date +%s%3N); [ $TARGET -gt $NOW ] && sleep $(( (TARGET - NOW + 999) / 1000 ))
if [ "$GAP_MS" -lt 5000 ]; then
  if has_text "up to date"; then VIS=yes; else VIS=no; fi; OBS=$(date +%s%3N)
  echo "restart observation: visible=$VIS at obs=${OBS}ms (clean < $((T2 + 4000)); jitter band $((T2 + 4000))-$((T2 + 5000)); past-deadline >= $((T2 + 5000)))"
  if [ "$OBS" -lt $((T2 + 4000)) ]; then
    if [ "$VIS" = yes ]; then
      echo "restart evidence: result still visible past the first tap's 5s deadline (qualified: UI-dump timing, deterministic inputs)"
    else
      echo "FAIL: result gone inside the second tap's own 5s window"; exit 1
    fi
  elif [ "$OBS" -lt $((T2 + 5000)) ]; then
    echo "WARN: observation inside the last-1s jitter band; restart check INCONCLUSIVE"
  else
    echo "WARN: observation landed past the second tap's deadline; restart check INCONCLUSIVE"
  fi
else
  has_text "up to date" || { echo "FAIL: up-to-date result missing after the repeat tap"; exit 1; }
fi
# Hidden assertion: past the SECOND tap's deadline plus margin, with the observation clock printed.
TARGET=$((T2 + 7000)); NOW=$(date +%s%3N); [ $TARGET -gt $NOW ] && sleep $(( (TARGET - NOW + 999) / 1000 ))
has_text "up to date" && { echo "FAIL: up-to-date still visible past the repeat tap's 5s deadline"; exit 1; } || echo "hide OK: result cleared after the last check's deadline (observed $(date +%s%3N)ms)"
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

# Keyboard and D-pad activation plus haptic on/off, observed on the running app. TAB moves focus to the first
# focusable control (its identity is printed, not asserted to be a particular button). D-pad center on it must be
# followed by a new app-attributed CLICK. Then the Vibrate on press row is tapped, its checked state is read before and
# after, key activation is repeated with it off and only the absence of a new app CLICK is observed, and the setting is
# restored and verified. No claim beyond the assertions below.
# Haptic proof: the newest TOUCH-usage CLICK vibration attributed to com.bumplog, by its own createTime on the device
# clock. A marker taken from the same device clock just before the action must be older than that event. Dump or clock
# failures fail the script; nothing is inferred from line counts, so a full history buffer cannot hide an event.
TS_RE='^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}$'
# Each helper prints ONE value on stdout and returns nonzero (message on stderr, never on stdout) on any failure, so a
# caller that writes X=$(helper) || exit 1 can never carry an error string into a comparison.
dev_now() {
  local t; t=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r') || { echo "device clock unreadable" >&2; return 1; }
  [[ "$t" =~ $TS_RE ]] || { echo "device clock not a timestamp" >&2; return 1; }
  printf '%s' "$t"
}
newest_app_click() {
  local D T; D=$(adb shell dumpsys vibrator_manager 2>&1) || { echo "vibrator dump failed" >&2; return 1; }
  case "$D" in *"VibratorController"*) ;; *) echo "vibrator dump unreadable" >&2; return 1 ;; esac
  T=$(printf '%s\n' "$D" | grep -F 'opPkg=com.bumplog' | grep -F 'effect=CLICK' | grep -F 'Usage=TOUCH' \
    | sed -n 's/.*createTime: \([0-9-]* [0-9:.]*\),.*/\1/p' | sort | tail -1)
  # No app click at all is a valid, empty answer; anything present must be a well-formed timestamp.
  if [ -n "$T" ]; then [[ "$T" =~ $TS_RE ]] || { echo "malformed click timestamp" >&2; return 1; }; fi
  printf '%s' "$T"
}
# click_after <click> <marker>: true only when both are well-formed and the click is strictly later.
click_after() { [[ "$1" =~ $TS_RE && "$2" =~ $TS_RE && "$1" > "$2" ]]; }
focused_line() { printf '%s\n' "$UI_XML" | tr '<' '\n' | grep -F 'focused="true"' | grep -v 'class="android.widget.FrameLayout"' | head -1; }
# The checked state lives on the toggleable row that CONTAINS the "Vibrate on press" text node (the text node itself is never
# checked), so find the smallest checkable node whose bounds enclose that text. Prints checked="true"/"false"; fails if absent.
vibrate_checked() {
  dump_ui || return 1
  printf '%s' "$UI_XML" | python3 -c '
import re,sys,xml.etree.ElementTree as ET
root=ET.fromstring(sys.stdin.read())
def bx(n):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n.get("bounds",""))
    return tuple(map(int,m.groups())) if m else None
t=[bx(n) for n in root.iter("node") if n.get("text")=="Vibrate on press" and bx(n)]
if len(t)!=1: sys.exit(2)
a=t[0]
c=[(bx(n),n) for n in root.iter("node") if n.get("checkable")=="true" and bx(n) and bx(n)[0]<=a[0] and bx(n)[1]<=a[1] and bx(n)[2]>=a[2] and bx(n)[3]>=a[3]]
if not c: sys.exit(3)
c.sort(key=lambda x:(x[0][2]-x[0][0])*(x[0][3]-x[0][1]))
print("checked=\"%s\"" % c[0][1].get("checked"))
' || { echo "vibrate row state unreadable" >&2; return 1; }
}
adb shell am force-stop com.bumplog; sleep 1
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --es reporticon mono; sleep 5
adb shell input keyevent KEYCODE_TAB; sleep 1
dump_ui
FL=$(focused_line)
[ -n "$FL" ] || { echo "FAIL: TAB did not focus any control"; exit 1; }
# The focused node is a merged container with no text of its own, so identify it by the text and descriptions of the
# nodes inside its bounds. Printed, and required to be non-empty with a real size.
FID=$(printf '%s' "$UI_XML" | python3 -c '
import re,sys,xml.etree.ElementTree as ET
root=ET.fromstring(sys.stdin.read())
def bx(n):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n.get("bounds",""))
    return tuple(map(int,m.groups())) if m else None
f=[n for n in root.iter("node") if n.get("focused")=="true" and n.get("class")!="android.widget.FrameLayout"]
if not f: sys.exit(2)
b=bx(f[0])
if not b or b[2]-b[0]<20 or b[3]-b[1]<20: sys.exit(3)
inside=[(n.get("text") or n.get("content-desc") or "") for n in root.iter("node") if bx(n) and bx(n)[0]>=b[0] and bx(n)[1]>=b[1] and bx(n)[2]<=b[2] and bx(n)[3]<=b[3]]
ident=" | ".join(t for t in inside if t)
if not ident: sys.exit(4)
print("bounds=%s identity=%s" % (b, ident[:160]))
') || { echo "FAIL: TAB focus could not be identified (status $?)"; exit 1; }
echo "focused control after TAB: $FID"
adb exec-out screencap -p > $OUT/v23-key-focus.png
M1=$(dev_now) || exit 1; sleep 1
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 1
E1=$(newest_app_click) || exit 1
adb shell dumpsys vibrator_manager > $OUT/v23-key-enter-vibrator.txt 2>&1 || { echo "FAIL: vibrator dump not saved"; exit 1; }
echo "key activation: marker=$M1 newest app CLICK=$E1"
click_after "$E1" "$M1" || { echo "FAIL: D-pad center on the focused control produced no app tick after the marker (haptics on)"; exit 1; }
adb exec-out screencap -p > $OUT/v23-key-after.png
shot v23-vibrate-before --es screen settings
V0=$(vibrate_checked) || exit 1; echo "Vibrate on press before: $V0"
[ "$V0" = 'checked="true"' ] || { echo "FAIL: Vibrate on press expected on (default), saw '$V0'"; exit 1; }
tap_text "Vibrate on press"
V1=$(vibrate_checked) || exit 1; echo "Vibrate on press after tap: $V1"
[ "$V1" = 'checked="false"' ] || { echo "FAIL: tapping the Vibrate on press row did not switch it off"; exit 1; }
adb exec-out screencap -p > $OUT/v23-vibrate-off.png
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home --es reporticon mono; sleep 5
M2=$(dev_now) || exit 1; sleep 1
adb shell input keyevent KEYCODE_TAB; sleep 1
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 1
E2=$(newest_app_click) || exit 1
echo "haptics off: marker=$M2 newest app CLICK=$E2"
# restore the original setting whatever the result, then judge
shot v23-vibrate-restore --es screen settings
tap_text "Vibrate on press"
V2=$(vibrate_checked) || exit 1; echo "Vibrate on press restored: $V2"
[ "$V2" = 'checked="true"' ] || { echo "FAIL: could not restore Vibrate on press"; exit 1; }
# Observed absence only: the off step has no independent proof that the key press did anything, so this checks that no
# app tick was recorded, and the log says so. The positive proof is the haptics-on step above.
if click_after "$E2" "$M2"; then echo "FAIL: an app tick fired after the marker with Vibrate on press off"; exit 1; fi
echo "haptics off: no app tick recorded after the marker (observed absence; key activation itself not separately asserted)"
# Compact pill is the only bottom bar now (the chooser is gone); these captures show it on several accents.
shot v24-pill-home --ez demo true --es screen home
shot v24-pill-stats --es screen stats
shot v24-pill-settings --es screen settings
# The pill on the lightest, a mid and the darkest swatch (dusty blue, mauve, burnt orange), then sage again.
shot v24-pill-blue --ez demo true --es screen home --ei accentidx 2
shot v24-pill-mauve --ez demo true --es screen home --ei accentidx 5
shot v24-pill-orange --ez demo true --es screen home --ei accentidx 1
shot v24-flat-restore --es screen settings --ei accentidx 0
# Tab-switch recording: every tab entered from every other, on a real screen recording, so first-frame jitter
# (empty state, zero counts, blank page, spacing shift) can be measured frame by frame. Never fatal.
mkdir -p tabsrec
adb shell am force-stop com.bumplog
adb shell rm -f /sdcard/tabs.mp4
adb shell screenrecord --time-limit 170 --bit-rate 8000000 /sdcard/tabs.mp4 &
REC=$!
# Whatever happens after this point, the recording and the full logcat are pulled on exit (never lost to a failed step).
pull_evidence() { trap - EXIT; adb shell pkill -2 screenrecord >/dev/null 2>&1; adb logcat -d -v time > tabsrec/logcat-full.txt 2>&1; sleep 1; adb pull /sdcard/tabs.mp4 tabsrec/tabs.mp4 >/dev/null 2>&1 || true; }
trap pull_evidence EXIT
sleep 2
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home; sleep 4
: > tabsrec/gfx.txt
gfx_phase() {  # name: print the render stats for the phase just run (janky frames and frame-time percentiles)
  { echo "== $1"; adb shell dumpsys gfxinfo com.bumplog | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands"; } | tee -a tabsrec/gfx.txt
}
tab_round() { for t in Review Stats Reported Settings Home Review; do tap_now "$t"; sleep 1; done; }
# Phase 1, cold: first visit of every tab in a fresh process.
adb shell dumpsys gfxinfo com.bumplog reset >/dev/null; echo "PHASE cold" ; tab_round; gfx_phase cold
# Phase 2, warm: the same tabs again, nothing changed.
adb shell dumpsys gfxinfo com.bumplog reset >/dev/null; echo "PHASE warm"; tab_round; gfx_phase warm
# Phase 3, post-write: a database write plus tick bump (the demo seed), then every tab again.
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home; sleep 3
adb shell dumpsys gfxinfo com.bumplog reset >/dev/null; echo "PHASE post-write"; tab_round; gfx_phase post-write
# Phase 4, second write, then the heavier tabs first.
adb shell am start -n com.bumplog/.MainActivity --ez demo true --es screen home; sleep 3
adb shell dumpsys gfxinfo com.bumplog reset >/dev/null; echo "PHASE post-write 2"; tab_round; gfx_phase post-write-2
# Phase 5, import/wipe under navigation: a real CSV import (3 rounds of 5000 spots) and a wipe run on a background thread
# while every tab is switched, so first-frame reads contend for the database monitor like a user importing and navigating.
# Nothing in this phase may stop the script: taps use coordinates captured before the stress starts, and after each tap a
# non-fatal UI dump says whether the app answered (a blocked main thread gives "null root node"). Every line carries a clock.
for t in Review Stats Reported Settings Home; do tap_now "$t"; eval "XY_$t=\"$TX $TY\""; sleep 1; done
nowt() { date +%H:%M:%S.%N | cut -c1-12; }
adb logcat -c
adb shell dumpsys gfxinfo com.bumplog reset >/dev/null
{ echo "== import-under-navigation clock log"; echo "$(nowt) start stressimport"; } | tee -a tabsrec/gfx.txt
adb shell am start -n com.bumplog/.MainActivity --ei stressimport 5000 --es screen home >/dev/null; sleep 1
for k in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18; do
  for t in Review Stats Reported Settings Home; do
    eval "set -- \$XY_$t"
    a=$(nowt); adb shell input tap $1 $2
    if dump_ui_soft; then r=answered; else r=NO-ANSWER; fi
    echo "$a tap $t -> $(nowt) ui $r" | tee -a tabsrec/gfx.txt
  done
  adb logcat -d -s BumpStress | grep -q "done" && break
done
echo "$(nowt) loop end" | tee -a tabsrec/gfx.txt
gfx_phase import-under-navigation
adb logcat -d -v time > tabsrec/logcat-full.txt 2>&1
{ echo "== logcat BumpStress, main-thread skipped frames, ANR"; adb logcat -d -v time | grep -E "BumpStress|Skipped [0-9]+ frames|ANR in|am_anr|Davey|Input dispatching timed out" | head -120; } | tee -a tabsrec/gfx.txt
{ echo "== focus and process"; adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -3; adb shell pidof com.bumplog; adb shell ls /data/anr 2>&1 | head -5; } | tee -a tabsrec/gfx.txt
adb exec-out screencap -p > tabsrec/after-import-phase.png
wait $REC 2>/dev/null; sleep 2
adb pull /sdcard/tabs.mp4 tabsrec/tabs.mp4 || echo "WARN: tab recording not pulled"
# Launcher proof: the real BumpLog icon as the device launcher draws it, on the home screen or in the app drawer.
# Never fatal (the other artifacts must still upload), but it says plainly whether the icon was found.
adb shell am force-stop com.bumplog; adb shell input keyevent KEYCODE_HOME; sleep 3
adb exec-out screencap -p > $OUT/v24-launcher-home.png
adb shell input swipe 540 2000 540 500 300; sleep 3
adb exec-out screencap -p > $OUT/v24-launcher-drawer.png
if dump_ui_soft && printf '%s' "$UI_XML" | grep -F 'BumpLog' | head -1 | grep -q .; then
  echo "launcher: a node labelled BumpLog is on the drawer screen: $(printf '%s' "$UI_XML" | tr '<' '\n' | grep -F 'BumpLog' | head -1 | grep -o 'bounds="[^"]*"')"
else
  echo "launcher: NO node labelled BumpLog found on the drawer screen; v24-launcher-drawer.png must be inspected by eye"
fi
adb shell input keyevent KEYCODE_HOME; sleep 1
ls -la $OUT
adb shell wm size reset
adb shell wm density reset
