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
# statusbar collapse first: the notification-tap section leaves the shade open on its WARN path,
# and a shade left open poisons every later capture AND every uiautomator dump (it reads the shade).
shot() { adb shell cmd statusbar collapse; adb shell am force-stop com.bumplog; sleep 1; adb shell am start -n com.bumplog/.MainActivity "${@:2}"; sleep 5; adb exec-out screencap -p > $OUT/$1.png; }
# dump_ui: fresh UI hierarchy into $UI_XML, fail-closed: a failed dump exits the script, so no
# assertion, scroll or tap can ever read a stale or missing /sdcard/ui.xml. Always called
# directly (never in a subshell or pipeline) so the exit really exits.
# dump_ui_soft: one dump attempt. 0 = fresh hierarchy in $UI_XML; 1 = no fresh dump. It clears
# $UI_XML first, so a failed attempt can never leave a stale document for a grep to read.
dump_ui_soft() {
  UI_XML=""
  adb shell rm -f /sdcard/ui.xml 2>/dev/null
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local X
  X=$(adb shell cat /sdcard/ui.xml 2>/dev/null) || return 1
  # A successful dump with an empty or invalid read must never pass an absence assertion.
  case "$X" in *"<hierarchy"*) UI_XML="$X"; return 0 ;; *) return 1 ;; esac
}
dump_ui() {
  adb shell rm -f /sdcard/ui.xml
  if ! adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; then echo "FAIL: uiautomator dump failed"; exit 1; fi
  UI_XML=$(adb shell cat /sdcard/ui.xml) || { echo "FAIL: could not read the UI dump"; exit 1; }
  # A successful dump with an empty or invalid read must never pass an absence assertion:
  # require a non-empty hierarchy document before any grep sees it.
  case "$UI_XML" in *"<hierarchy"*) ;; *) echo "FAIL: UI dump has no hierarchy root"; exit 1 ;; esac
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
# Bottom bar styles (owner-approved trial): same Home screen under all three.
shot v21-bar-pill --ez demo true --es screen home --es barstyle pill
shot v21-bar-glass --ez demo true --es screen home --es barstyle glass
shot v21-bar-flat --ez demo true --es screen home --es barstyle flat

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
tap_now "Check for updates"; T1=$(date +%s)
FOUND=0
for i in $(seq 1 8); do dump_ui_soft && printf '%s\n' "$UI_XML" | grep -qF "up to date" && { FOUND=1; break; }; sleep 1; done
[ "$FOUND" = 1 ] || { echo "FAIL: up-to-date result not shown after tap"; exit 1; }
adb exec-out screencap -p > $OUT/v21-uptodate-shown.png
# Second tap via the cached point: the button cannot have moved - in Settings.kt the result
# text appears in the version ROW above it, and the button is a separate full-width Box with
# fixed height; only its label toggles. Skipping the re-dump lands T2 ~2.5s after T1, inside
# the 5s window. A gap >= 5s stays the WARN/inconclusive branch below, never a failure.
retap_last; T2=$(date +%s)
GAP=$((T2 - T1))
if [ "$GAP" -ge 5 ]; then echo "WARN: repeat tap landed ${GAP}s after the first; restart window missed, checking fresh-timer behavior only"; fi
# Visible assertion: past the FIRST tap's deadline (proves the restart extended it), before the second's.
TARGET=$((T1 + 6)); NOW=$(date +%s); [ $TARGET -gt $NOW ] && sleep $((TARGET - NOW))
if [ "$GAP" -lt 5 ]; then
  has_text "up to date" && echo "timer restart OK: result visible past the first tap's 5s deadline" || { echo "FAIL: identical result did not restart the 5s timer"; exit 1; }
else
  has_text "up to date" || { echo "FAIL: up-to-date result missing after the repeat tap"; exit 1; }
fi
# Hidden assertion: past the SECOND tap's deadline plus margin.
TARGET=$((T2 + 7)); NOW=$(date +%s); [ $TARGET -gt $NOW ] && sleep $((TARGET - NOW))
has_text "up to date" && { echo "FAIL: up-to-date still visible past the repeat tap's 5s deadline"; exit 1; } || echo "hide OK: result cleared after the last check's deadline"
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
