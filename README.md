# BumpLog

After destroying one too many tires on bad roads, I built BumpLog - an app that reports bad roads to the authorities, and files a damage report when they don't get fixed and my car pays for it.

It logs hard hits by watching the accelerometer and GPS while you drive, and warns you on approach the next time you reach the same spot. At your destination you can email the report to your AI assistant - Instinct, Tab, or anything else with an inbox - and it works out who owns the road (city, county, state DOT) and prepares the repair request for your approval.

Built for highways with violent height steps between pavement sections, not only city street potholes.

## How it works

**Drive detection.** Auto-starts when your phone connects to your car's Bluetooth, or start it by hand. The accelerometer logs a hit at 0.80 g and a severe hit at 1.30 g (both adjustable), ignoring anything under 15 mph. Hits within 30 m in the same direction group into one spot. A Driver / Passenger switch on the home screen stops logging when you are not driving.

**Approach alerts.** Saved spots warn you about a quarter mile out, with a final chime at about 600 ft. Alerts name the defect once you have confirmed its type ("Major pothole ahead"). Tone, voice or both, all previewable in Settings.

**In-drive logging.** A major bump pops up "Major bump detected." with Log it / Ignore / Warn me here forever. Log it opens a short form: defect type (a labeled guess you confirm), major or minor, and whether to file at the end of the trip. The pop-up dismisses itself after 10, 20 or 30 seconds (default 20) or never, set in Settings.

**End-of-trip email.** One notification opens one email in your mail app covering the spots you marked. You press send; nothing goes out by itself. Review at your destination to confirm or dismiss each spot first.

**Damage reports.** Pick one of your recent hits, say what was damaged, add photos, and send now or at the end of the drive. The report quotes the measured jolt from the phone accelerometer. It is not proof of force or damage.

**Scorecard.** Reports logged, filed and sent, times avoided (a warned spot's alert fired and you passed it again), avoided by type, most avoided, and your largest measured jolt.

Also: local report IDs, CSV export and import (1 MB limit), and a "Fixed?" suggestion after two clean passes through a spot.

## Install

1. Download `BumpLog-v1.0.0.apk` from the [latest release](../../releases/latest) on your phone and open it. Allow installs from your browser when Android asks.
2. Open BumpLog and follow the setup: your assistant's email address, location (choose **Allow all the time**), notifications and Bluetooth.

More detail on filing, privacy and battery setup is in [docs/details.md](docs/details.md). Hit detection and alert timing have not been tuned on real roads yet.

## License

GNU General Public License v3.0 (GPL-3.0). Copyright (C) 2026 Ezzy (MtndaleRedneck). See [LICENSE](LICENSE).
