# status-icon-spacing v3 (diagnostic, measurement only, no app build)
Single bounded run (inputs: api default 34, dpi default 480). Released APKs only. Emulator, not the user's phone.
Triggers (from source): BumpLog = BOOT_COMPLETED to exported BootReceiver -> "Logging paused" alert (HIGH, ic_stat).
ListenLater = ACTION_SEND to exported ShareActivity -> sign-in notice (DEFAULT, ic_notification).
Messages = stock SMS control if present; else ShellPost (`cmd notification post`, light theme only). Image is google_apis (userdebug) with `adb root`, so the protected BOOT_COMPLETED shell broadcast is permitted; stderr kept in trigger_stderr.txt. BingeMote and HomeRoom = NOT RUN (need a live PC/VLC or enrolled server).
BumpLog's persistent notification is IMPORTANCE_MIN and shows no status-bar icon, by design of Android.
Invalid conditions print a reason and no numbers (measure.py).
