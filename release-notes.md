BumpLog 1.19.0

November 25, 2026. Buttons that press in, and your own report icon.

New:
- Buttons and the bottom bar sink in when you press them, with a light vibration tick. A new Vibrate on press switch in Settings turns the tick off.
- Report last hit now carries the BumpLog mark instead of the warning triangle. A new Report icon setting switches it between Monochrome and Full color.

Changed:
- The bright orange accent is gone from the color picker. Anyone who had it picked is back on sage.
- The bottom bar stays flat by default, and Glass is gone. Settings has a Bottom bar choice for a Compact pill look if you prefer it.
- The Updates card now matches the other apps: the button first, then the version with "You're up to date." beside it for 5 seconds.
- The Feedback & bugs screen no longer ends with a Done button.

BumpLog 1.18.0

November 25, 2026. A freshened Settings and a modernized app.

New:
- Settings has a new layout. A Feedback & bugs row (with a clearer arrow) opens the screen for sending feedback, reporting a bug or suggesting a feature, and a version card has a "Check for updates" button - the answer ("You're up to date.") shows next to the version and fades away after 5 seconds.
- Settings keeps your place when you open a screen and come back; leaving Settings fully starts you back at the top.
- Appearance has a Bottom bar setting so you can try three bar looks (Current, Pill, Glass).
- The accent colours are a new muted set. A colour you picked before that is no longer offered becomes sage.
- Notifications use a white version of the BumpLog logo.

Under the hood:
- BumpLog is rebuilt on Android SDK 37 with Android Gradle plugin 9.3.1 and Kotlin 2.4.21, and the release app now strips out unused code and resources.

BumpLog 1.17.2

November 24, 2026. Share-sheet and restore reliability fixes.

Fixed:
- When you share a damage report, BumpLog now checks apps the same way it actually hands them the email and photos, so only apps that can really take it show up. ([021fb50](https://github.com/MtndaleRedneck/BumpLog/commit/021fb50d6fcd009d1a9e2370435af4f671d4e0d6))
- A restore now keeps track of exactly which photos it copied. If a restore stops partway (the app closes or the phone restarts), running Restore again keeps the photos already copied and finishes the rest. ([c434fb5](https://github.com/MtndaleRedneck/BumpLog/commit/c434fb5c9349ad6c9afa34dad59c04b32a54065a), [cac8779](https://github.com/MtndaleRedneck/BumpLog/commit/cac87793fc11fd625575e77c2374e3651268d579), [193550d](https://github.com/MtndaleRedneck/BumpLog/commit/193550d4bbe4795172f59e7a546fc29f6b1db662), [ac7a68a](https://github.com/MtndaleRedneck/BumpLog/commit/ac7a68acaf88e919cadfe41abd78ddb0168ad7d6))
- Photos that only share a name with a restored one are no longer picked up by mistake. ([193550d](https://github.com/MtndaleRedneck/BumpLog/commit/193550d4bbe4795172f59e7a546fc29f6b1db662))

BumpLog 1.17.1

- A restore that can't finish copying photos, voice clips or settings now says so and asks you to run Restore again, instead of looking done.
- Restore is stricter about broken backup files: too many entries, oversized voice clips, duplicate clips and archives past the size cap are rejected.

BumpLog 1.17.0

- Logging no longer stops silently when Android restarts it in the background, and the weekly auto backup works even when the app was closed.
- Screen off no longer means missed bumps: logging holds the phone awake while you drive.
- When GPS drops (tunnel, location off), BumpLog says "GPS lost - not logging" instead of showing a live speed, and the auto-off and trip split wait for GPS instead of acting on a guess.
- After a phone restart with Power on, a "Logging paused" notice offers to start logging again. It never starts by itself.
- Bumps logged on a shaky GPS fix no longer move a spot's pin, and the shaky flag is saved with the hit.
- "Delete all data" now offers "Back up first", and its wording matches what is actually erased. Delete is always your own separate tap. With an auto-backup folder, the fresh backup is read back complete before it counts.
- In Tone only mode, the quarter-mile warning now beeps like the close warning.
- Music stays dipped for the whole alert when two alerts overlap, instead of coming back up mid-sentence.
- Auto backup keeps the newest backup of each of the last 8 days (never fewer than 2), and a failed save no longer leaves a broken zip behind.
- Faster while driving: warned spots are looked up only when they change, and hit matching reads a small map box instead of the whole list.

BumpLog 1.16.1

- Fix the setup email (and damage reports without photos) opening in the mail app with an empty subject and message

BumpLog 1.16.0

- A report now counts as sent only after you say the email went out. After the mail app opens, BumpLog asks "Did the email go out?" - "Not yet" keeps the report in the queue.
- New auto backup: pick a folder once and the full backup saves there weekly and after each trip email. The Backup card shows the last save.
- Hit pins land where the car was, not where it is by the time you look. If logging stops, a notification tells you and one tap turns it back on.
- Alerts duck your music instead of fighting it, the voice waits for the tone to finish, and the beeps play on the same volume as the other tones.
- A drive now ends on its own after a while indoors.
- Reported: rows open their spot from the title. Report last hit skips spots you already dealt with.
- Buttons and widgets no longer clip at large text.
- The update offer only waits while you are actually driving.
- Small wording fixes: real distances like "1,200 ft", "No hits yet this drive.", and clearer error messages.
- The assistant guide is now Instructions v4, with new where-to-file notes.

BumpLog 1.15.1

- Marking how a report turned out gets a fourth button: Waiting on the city. Fixed is green, and Unclear is now Unclear response - the Reported tab uses the same words.
- Settings: Feedback & bugs and Updates cards match the other apps. Updates sits at the bottom, with a Check for updates button and the version inside the card.
- The update check is always on - the old switch is gone. Checking by hand answers even while driving.
- The app is dark only now.

BumpLog 1.15.0

- New Reported tab: every bump you reported, with what happened - waiting on the city, fixed, unclear, or won't fix. Tap one to mark how it turned out.
- Report a bump again from the same place when the city doesn't act; each new report carries a fresh number, so the city sees the count.
- The Reported tab gets a paper plane icon, tipped up at an angle.
- Long Review lists get a floating Top button that jumps back to the start.
- Every screen now remembers where you scrolled to when you leave and come back.
- A reported (greyed) Review row no longer lets the swipe "Dismiss" hint show through it.
