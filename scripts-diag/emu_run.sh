#!/usr/bin/env bash
# Controlled status-bar capture on an EMULATOR. Env: API, DPI. Released APKs only; no builds.
# AOSP-style bar, NOT One UI, NOT the user's phone. Label every result "emulator".
#
# Per-app triggers (all from reading each app's source, exported entry points only, no accounts, no server):
#  BumpLog     : explicit broadcast BOOT_COMPLETED -> BootReceiver (exported) -> Notifier.loggingPaused
#                (channel "alerts", IMPORTANCE_HIGH, small icon R.drawable.ic_stat). Prefs.detectionEnabled defaults true.
#                NOTE: BumpLog's persistent "watching the road" notification is IMPORTANCE_MIN, which shows NO status-bar
#                icon, so it can never be the icon beside the clock. Only alert-channel notifications can.
#  ListenLater : ACTION_SEND text/plain to exported ShareActivity with a YouTube URL -> ShareWorker; with no saved login
#                it posts notifySignInOnce (channel IMPORTANCE_DEFAULT, ic_notification). Needs network (emulator has it).
#  BingeMote   : NOT TRIGGERABLE here. Its only small-icon post (MediaControls, ic_stat_bingemote) needs a live PC/VLC
#                connection. Logged as INVALID/NOT RUN, never inferred.
#  HomeRoom    : NOT TRIGGERABLE here. Its notifications need an enrolled server session (and the release manifest
#                forbids cleartext, so the repo's localhost stub cannot be used). Logged as INVALID/NOT RUN.
#  Messages    : stock control via `adb emu sms send`, if com.google.android.apps.messaging is on the image.
set +e
OUT=out; mkdir -p $OUT; A="adb shell"
adb root >> $OUT/adb_root.txt 2>&1; adb wait-for-device; sleep 3; echo "uid=$(adb shell id)" >> $OUT/adb_root.txt
ERR=$OUT/trigger_stderr.txt; : > $ERR
DPI=${DPI:-480}; API=${API:-34}
$A wm size 1080x2340; $A wm density $DPI
$A settings put system font_scale 1.0
$A settings put global sysui_demo_allowed 1
demo() { $A am broadcast -a com.android.systemui.demo "$@" >/dev/null; }
demo_on() {
  demo -e command enter; demo -e command clock -e hhmm 0302
  demo -e command battery -e level 100 -e plugged false
  demo -e command network -e wifi show -e level 4
  demo -e command network -e mobile show -e datatype none -e level 4
  demo -e command notifications -e visible true
}
{ echo "api(sdk)=$($A getprop ro.build.version.sdk | tr -d '\r')"; $A getprop ro.build.fingerprint; $A wm size; $A wm density; echo "font_scale=$($A settings get system font_scale)"; $A settings get global theme_customization_overlay_packages; } > $OUT/config.txt
$A pm list packages -s | grep -i -E "messag|gmail|dialer|chrome|clock|maps" > $OUT/preinstalled_candidates.txt
$A dumpsys window | grep -i -E "statusBars|status_bar_height|mStatusBarHeight" | head -8 > $OUT/statusbar_height.txt
$A cmd overlay dump android 2>/dev/null | grep -i status_bar_height >> $OUT/statusbar_height.txt
LOG=$OUT/trigger_log.txt; : > $LOG

relayout() { $A cmd statusbar expand-notifications; sleep 1; $A cmd statusbar collapse; sleep 1; }
cap() { # name count : status bar over the Settings page, raw lossless PNG
  for i in $(seq 1 $2); do relayout; $A am start -a android.settings.SETTINGS >/dev/null; sleep 2
    adb exec-out screencap -p > "$OUT/$1_r$i.png"; done
}
dump() { $A dumpsys notification --noredact | grep -E "NotificationRecord|importance|icon=|smallIcon|largeIcon|color=" > "$OUT/$1.notif.txt"; }
posted() { $A dumpsys notification --noredact | grep -c "NotificationRecord.*pkg=$1"; }
chan() { $A dumpsys notification --noredact | grep -A12 "NotificationRecord.*pkg=$1" | grep -i -E "channel|importance" | head -4; }
pkgcheck() { # spec-declared package vs aapt2 from the APK
  local got; got=$("$(ls -d $ANDROID_HOME/build-tools/* | tail -1)/aapt2" dump packagename "$2" 2>/dev/null)
  [ "$got" = "$1" ] && echo ok || echo "MISMATCH(apk says $got)"; }
install_grant() { adb install -r -g "apks/$2.apk" >/dev/null 2>&1
  for p in POST_NOTIFICATIONS; do $A pm grant $1 android.permission.$p 2>/dev/null; done; }
fire() { # spec pkg
  case $1 in
    BumpLog) # BootReceiver (exported) acts only if action==BOOT_COMPLETED and Prefs.detectionEnabled (default true, Prefs.kt b("detection", true));
             # no other condition. Launch once first so the package is not in the stopped state, then explicit-component broadcast (-f 32 = include stopped).
             $A monkey -p com.bumplog -c android.intent.category.LAUNCHER 1 >> $ERR 2>&1; sleep 5; $A input keyevent KEYCODE_HOME; sleep 1
             $A am broadcast -f 32 -a android.intent.action.BOOT_COMPLETED -n com.bumplog/.BootReceiver >> $ERR 2>&1 ;;
    ListenLater) $A am start -n com.listenlater/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=dQw4w9WgXcQ" >> $ERR 2>&1
                 sleep 3; $A input keyevent KEYCODE_HOME ;;
  esac
  for t in $(seq 1 15); do sleep 2; [ "$(posted $2)" -gt 0 ] && break; done   # poll up to 30 s for the worker
  sleep 12                                                                    # let any heads-up retire so the bar icon is visible
  $A cmd statusbar collapse
}
declare -A PKG=( [BumpLog]=com.bumplog [ListenLater]=com.listenlater )
log() { echo "$*" >> $LOG; }

for TH in dark light; do
  $A cmd uimode night $([ $TH = dark ] && echo yes || echo no); sleep 3
  demo_on
  $A pm clear com.google.android.apps.messaging >/dev/null 2>&1
  cap base_$TH 5; dump base_$TH
  log "$TH|baseline|-|posted=0|ok"
  for spec in BumpLog ListenLater; do
    pkg=${PKG[$spec]}; ok=$(pkgcheck $pkg apks/$spec.apk)
    install_grant $pkg $spec; fire $spec $pkg
    n=$(posted $pkg); log "$TH|$spec|$pkg|posted=$n|pkgcheck=$ok"; chan $pkg > "$OUT/chan_${spec}_$TH.txt"
    dump solo_${spec}_$TH
    [ "$n" -gt 0 ] && cap solo_${spec}_$TH 5
    log "$TH|${spec}_after|$pkg|posted=$(posted $pkg)|ok"
    # shade (expanded) capture
    $A cmd statusbar expand-notifications; sleep 2; adb exec-out screencap -p > "$OUT/solo_${spec}_${TH}_shade.png"; $A cmd statusbar collapse
    $A pm uninstall $pkg >/dev/null; sleep 2
  done
  log "$TH|BingeMote|com.bingemote|posted=0|NOT_RUN no offline trigger (needs live PC/VLC connection)"
  log "$TH|HomeRoom|app.homeroom|posted=0|NOT_RUN no offline trigger (needs enrolled server session)"
  if $A pm list packages | grep -q com.google.android.apps.messaging; then
    $A cmd role add-role-holder android.app.role.SMS com.google.android.apps.messaging 2>/dev/null
    adb emu sms send 5551234 "spacing test"; sleep 15
    n=$(posted com.google.android.apps.messaging); log "$TH|Messages|com.google.android.apps.messaging|posted=$n|ok"
    dump solo_Messages_$TH; [ "$n" -gt 0 ] && cap solo_Messages_$TH 5
    # combined: Messages + BumpLog + ListenLater, fixed post order, all present together
    for spec in BumpLog ListenLater; do install_grant ${PKG[$spec]} $spec; fire $spec ${PKG[$spec]}; done
    dump combined_$TH; cap combined_$TH 5
    # non-demo comparison (real clock, real system icons); clock text varies, so flagged separately
    demo -e command exit; sleep 3; cap nodemo_combined_$TH 3; demo_on
    for spec in BumpLog ListenLater; do $A pm uninstall ${PKG[$spec]} >/dev/null; done
    $A pm clear com.google.android.apps.messaging >/dev/null 2>&1
  else
    log "$TH|Messages|-|posted=0|NOT_ON_IMAGE (userdebug google_apis normally has no Google Messages)"
  fi
  # Shell-posted stock control (platform default small icon; unverified what glyph). Only in the LAST theme, because
  # the notification cannot be cleared between themes without a reboot and would contaminate the next baseline.
  if [ $TH = light ] && ! $A pm list packages | grep -q com.google.android.apps.messaging; then
    $A cmd notification post -t "Control" spacingctl "spacing test" >> $ERR 2>&1; sleep 15
    n=$(posted com.android.shell); log "$TH|ShellPost|com.android.shell|posted=$n|ok"
    dump solo_ShellPost_$TH; [ "$n" -gt 0 ] && cap solo_ShellPost_$TH 5
    for spec in BumpLog ListenLater; do install_grant ${PKG[$spec]} $spec; fire $spec ${PKG[$spec]}; done
    dump combined_$TH; cap combined_$TH 5
    demo -e command exit; sleep 3; cap nodemo_combined_$TH 3; demo_on
  fi
done
echo done
