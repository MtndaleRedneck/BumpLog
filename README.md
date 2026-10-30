<p align="center"><img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="BumpLog icon" width="96"></p>

# BumpLog

After destroying one too many tires on bad roads, I built BumpLog - an app that reports bad roads to the authorities, and files a damage report when they don't get fixed and my car pays for it.

It logs hard hits by watching the accelerometer and GPS while you drive, and warns you on approach the next time you reach the same spot. At your destination you can email the report to your AI assistant - Instinct, Tab, or anything else with an inbox - and it works out who owns the road (city, county, state DOT) and prepares the repair request for your approval.

Built for highways with violent height steps between pavement sections, not only city street potholes.

## How it works

**Drive detection.** Turning Power on starts logging and keeps it running until you turn Power off, or until the phone has been stopped for a set time (30 minutes by default, adjustable from 10 to 120; slow traffic does not count as stopped). An optional setting, off by default, lets you pick your car's Bluetooth device: BumpLog then offers a notification to start logging when it connects and to stop when it disconnects. The accelerometer logs a hit at 0.85 g and a severe hit at 1.30 g (both adjustable), ignoring anything under 15 mph. Hits within 30 m in the same direction group into one spot. A Driver / Passenger switch on the home screen stops logging when you are not driving. Passenger goes back to Driver after 3 hours (waiting until you are stopped), with a heads-up, Extend and Undo.

**Approach alerts.** Saved spots warn you about a quarter mile out, with a final chime at about 600 ft. Alerts name the defect once you have confirmed its type ("Major pothole ahead"). Tone, voice, or both, all previewable in Settings. Six built-in natural voices (Adam, Michael, Default, Bella, Sarah, Sky) are pre-recorded clips of the alert phrases, so they work offline and add under 1 MB; other voices use your phone's text-to-speech.

**In-drive logging.** A major bump pops up "Major bump detected." with Log it / Ignore / Warn me here forever. Log it opens a short form: defect type (a labeled guess you confirm), major or minor, and whether to file at the end of the trip. The pop-up dismisses itself after 10, 20 or 30 seconds (default 20) or never, set in Settings.

**End-of-trip email.** One notification opens one email in your mail app covering the spots you marked. You press send; nothing goes out by itself. Review at your destination to confirm or dismiss each spot first.

**Damage reports.** Pick one of your recent hits, say what was damaged, add photos, and send now or at the end of the drive. The report quotes the measured hit strength from the phone accelerometer. It is not proof of force or damage.

**Stats.** Reports sent, times avoided (a warned spot's alert fired and you passed it again), avoided by type, most avoided, and your hardest measured hit.

**Testing mode.** Settings can fire the real alerts and screens without driving, using TEST spots that never count toward your stats.

Also: running report numbers assigned at confirm, CSV export and import (1 MB limit), and a "Fixed?" suggestion after two clean passes through a spot.

## Install

1. Download the APK from the [latest release](../../releases/latest) on your phone.
2. Open it and allow installs from your browser when Android asks.
3. Open BumpLog and follow the setup: add your assistant address, send the setup email so the assistant knows what to do, allow location (choose **Allow all the time**), notifications, and Bluetooth only if you want the car prompts.
4. Tap **Battery setup** in Settings. On Samsung phones also add the app to Settings > Battery > Background usage limits > Never sleeping apps, or One UI can stop it mid-drive.

More detail on screens, filing, privacy, known limits, and building is in [docs/details.md](docs/details.md).
