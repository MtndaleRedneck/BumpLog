# BumpLog

After destroying one too many tires on bad roads, I built BumpLog - an app that reports bad roads to the authorities, and files a damage report when they don't get fixed and my car pays for it.

It logs hard hits by watching the accelerometer and GPS while you drive, and warns you on approach the next time you reach the same spot. At your destination you can email the report to your AI assistant - Instinct, Tab, or anything else with an inbox - and it works out who owns the road (city, county, state DOT) and prepares the repair request for your approval.

Built for highways with violent height steps between pavement sections, not only city street potholes.

> Auto-start, passive GPS battery use and the hit thresholds need real drives to tune. See [Known limits](#known-limits).

## Screens

Captured by CI from the debug build running on an Android emulator, with demo data seeded. Visually checked for layout and text. Sensors and driving behaviour are not exercised by these.

| | | | |
|---|---|---|---|
| ![Home](docs/screenshots/home.png) | ![Severe hit](docs/screenshots/alert.png) | ![Review](docs/screenshots/review.png) | ![Spot](docs/screenshots/spot.png) |
| ![Damage report](docs/screenshots/damage.png) | ![Settings](docs/screenshots/settings.png) | ![Settings 2](docs/screenshots/settings-2.png) | ![Settings 3](docs/screenshots/settings-3.png) |

## How it works

**Drive detection.** Auto-starts when your phone connects to your car's Bluetooth, or start it by hand. The accelerometer logs a hit at 0.80 g and a severe hit at 1.30 g (both adjustable), ignoring anything under 15 mph. Hits within 30 m in the same direction group into one spot. A Driver / Passenger switch on the home screen stops logging when you are not driving.

**Approach alerts.** Saved spots warn you about a quarter mile out, with a final chime at about 600 ft. Alerts name the defect once you have confirmed its type ("Major pothole ahead"). Tone, voice or both, all previewable in Settings.

**In-drive logging.** A major bump pops up "Major bump detected." with Log it / Ignore / Warn me here forever. Log it opens a short form: defect type (a labeled guess you confirm), major or minor, and whether to file at the end of the trip. The pop-up dismisses itself after 10, 20 or 30 seconds (default 20) or never, set in Settings.

**End-of-trip email.** One notification opens one email in your mail app covering the spots you marked. You press send; nothing goes out by itself. Review at your destination to confirm or dismiss each spot first.

**Damage reports.** Pick one of your recent hits, say what was damaged, add photos, and send now or at the end of the drive. The report quotes the measured jolt from the phone accelerometer. It is not proof of force or damage.

**Scorecard.** Reports logged, filed and sent, times avoided (a warned spot's alert fired and you passed it again), avoided by type, most avoided, and your largest measured jolt.

**Testing mode.** Settings can fire the real alerts and screens without driving, using TEST spots that never count toward your stats.

Also: local report IDs, CSV export and import (1 MB limit), and a "Fixed?" suggestion after two clean passes through a spot.

## Install

1. Download `BumpLog-v1.0.0.apk` from the [v1.0.0 release](../../releases/tag/v1.0.0) on your phone.
2. Open it and allow installs from your browser when Android asks.
3. Open BumpLog and follow the setup: set your assistant address, allow location (choose **Allow all the time**), notifications and Bluetooth.
4. Tap **Battery setup** in Settings. On Samsung phones also add the app to Settings > Battery > Background usage limits > Never sleeping apps, or One UI can stop it mid-drive.

Release APKs are built by GitHub Actions and signed with a private key held in encrypted repository secrets. The key is not in this repo. If you build the app yourself, your build is signed with your own key and will not install over a release APK. Needs Android 12 or newer.

## How filing works

The app has no server and no accounts. It cannot send email by itself. **Send** opens your mail app with the report already written, addressed to your assistant, and you press send there.

- **Sent** means the report was handed to your mail app. It does not mean it arrived.
- **Filed** appears only after your assistant confirms a request was actually accepted. Nothing is ever marked Filed automatically. The assistant can reply with a link of the form `bumplog://filed?id=BL-20261011-ABCD&ref=<reference number>`. Tapping it opens a confirmation showing the ID and reference. The report is marked Filed only after you tap Mark Filed. The reference number is optional. A link for an unknown report ID is rejected, and a different reference never replaces an existing one without asking.
- Each report email has a plain-text block: report ID, defect type and category, severity, hit count, coordinates, a map link, direction and timestamps. Speed is never included. Every email opens with a short "About this email" block that explains what it is and what the assistant should do. One assistant address per report.
- Damage reports are separate emails, marked as damage claims, with the impact details and any photos you added. A damage report stands on its own and does not need a road defect report to be sent first.
- Nothing is submitted to a city or agency without your approval. The email says so to the assistant.

**Assistant picker.** Choose Instinct, Tab or Custom. Presets are empty slots you fill in once on first setup, so the app ships with no personal addresses. Pick a preset and it fills in the address you saved. **Send test report** and **Test email** send an email labeled TEST. **Set up your assistant** in Settings copies a short briefing you can paste into your own AI chat. A setup link (`bumplog://setup?assistant=instinct&email=you@example.com`) opens a confirmation showing the address. Nothing is saved until you tap Use address.

## Privacy

- Runs offline. The only network use is your mail app sending the email you approve, and the phone's own geocoder naming roads (optional, falls back to coordinates).
- No accounts, no analytics, no backend. Data is stored in a local database on your phone. Settings > Delete all logged data erases it.
- The Feedback entry ("General feedback, bugs & feature suggestions") opens a screen where you see exactly what would be sent before you choose a GitHub issue or the private form. It only adds the app version, Android version and phone model. Never your location or logs.

## Known limits

v1.0.0 builds in CI and passes its unit tests. The screens render on an Android emulator (see screenshots above). Hit detection and alert timing have not been tuned on real roads.

- Auto-start on car Bluetooth is a best effort. Android limits starting background services, so if it refuses, you get a "Car connected, tap to start" notification instead.
- Hit detection uses fixed thresholds and simple pattern rules. Real mount and car tuning will change them.
- Defect-type guessing is a heuristic. Always confirm.
- "Fixed?" is offered after two clean passes through a spot. It is a suggestion, not proof.
- No QR setup yet. The test report and setup link cover first setup.

## Build

```
./gradlew assembleRelease
```

Needs JDK 17 and the Android SDK 34. GitHub Actions builds the APK on every push to `main` and publishes a release when the version number is new. Without the signing secrets the release build is unsigned.

## License

GNU General Public License v3.0 (GPL-3.0). Copyright (C) 2026 Ezzy (MtndaleRedneck). See [LICENSE](LICENSE).
