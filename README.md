# BumpLog

After destroying one too many tires on bad roads, I built BumpLog - an app that reports bad roads to the authorities, and files a damage report when they don't get fixed and my car pays for it.

It logs hard hits by watching the accelerometer and GPS while you drive, and warns you on approach the next time you reach the same spot. At your destination you can email the report to your AI assistant - Instinct, Tab, or anything else with an inbox - and it works out who owns the road (city, county, state DOT) and prepares the repair request for your approval.

Built for highways with violent height steps between pavement sections, not only city street potholes.

> Auto-start, passive GPS battery use, and the hit thresholds need real drives to tune. See [Known limits](docs/details.md#known-limits).

## How it works

**Drive detection.** Auto-starts when your phone connects to your car's Bluetooth, or start it by hand. The accelerometer logs a hit at 0.85 g and a severe hit at 1.30 g (both adjustable), ignoring anything under 15 mph. Hits within 30 m in the same direction group into one spot. A Driver / Passenger switch on the home screen stops logging when you are not driving.

**Approach alerts.** Saved spots warn you about a quarter mile out, with a final chime at about 600 ft. Alerts name the defect once you have confirmed its type ("Major pothole ahead"). Tone, voice, or both, all previewable in Settings. Six built-in natural voices (Adam, Michael, Default, Bella, Sarah, Sky) are pre-recorded clips of the alert phrases, so they work offline and add under 1 MB; other voices use your phone's text-to-speech.

**In-drive logging.** A major bump pops up "Major bump detected." with Log it / Ignore / Warn me here forever. Log it opens a short form: defect type (a labeled guess you confirm), major or minor, and whether to file at the end of the trip. The pop-up dismisses itself after 10, 20 or 30 seconds (default 20) or never, set in Settings.

**End-of-trip email.** One notification opens one email in your mail app covering the spots you marked. You press send; nothing goes out by itself. Review at your destination to confirm or dismiss each spot first.

**Damage reports.** Pick one of your recent hits, say what was damaged, add photos, and send now or at the end of the drive. The report quotes the measured hit strength from the phone accelerometer. It is not proof of force or damage.

**Stats.** Reports logged, filed, and sent, times avoided (a warned spot's alert fired and you passed it again), avoided by type, most avoided, and your hardest measured hit.

**Testing mode.** Settings can fire the real alerts and screens without driving, using TEST spots that never count toward your stats.

Also: local report IDs, CSV export and import (1 MB limit), and a "Fixed?" suggestion after two clean passes through a spot.

## Install

1. Download the APK from the [latest release](../../releases/latest) on your phone.
2. Open it and allow installs from your browser when Android asks.
3. Open BumpLog and follow the setup: set your assistant address, allow location (choose **Allow all the time**), notifications and Bluetooth.
4. Tap **Battery setup** in Settings. On Samsung phones also add the app to Settings > Battery > Background usage limits > Never sleeping apps, or One UI can stop it mid-drive.

Release APKs are built by GitHub Actions and signed with a private key held in encrypted repository secrets. The key is not in this repo. If you build the app yourself, your build is signed with your own key and will not install over a release APK. Needs Android 12 or newer.

More detail on screens, filing, privacy, known limits, and building is in [docs/details.md](docs/details.md).

## License

GNU General Public License v3.0 (GPL-3.0). Copyright (C) 2026 Ezzy (MtndaleRedneck). See [LICENSE](LICENSE).
