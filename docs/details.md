# Details

## How filing works

The app has no server and no accounts. It cannot send email by itself. **Send** opens your mail app with the report already written, addressed to your assistant, and you press send there.

- **Sent** means the report was handed to your mail app. It does not mean it arrived.
- **Filed** appears only after your assistant confirms a request was actually accepted. Nothing is ever marked Filed automatically. The assistant can reply with a link of the form `bumplog://filed?id=BL-20261011-ABCD&ref=<reference number>`. Tapping it opens a confirmation showing the ID and reference. The report is marked Filed only after you tap Mark Filed. The reference number is optional. A link for an unknown report ID is rejected, and a different reference never replaces an existing one without asking.
- Each report email has a plain-text block: report ID, defect type and category, severity, hit count, coordinates, a map link, direction, speed and timestamps. One assistant address per report.
- Damage reports are separate emails, marked as damage claims, with the impact details and any photos you added.
- Nothing is submitted to a city or agency without your approval. The email says so to the assistant.

**Assistant picker.** Choose Instinct, Tab or Custom. Presets are empty slots you fill in once on first setup, so the app ships with no personal addresses. Pick a preset and it fills in the address you saved. **Send test report** and **Test email** send an email labeled TEST. A setup link (`bumplog://setup?assistant=instinct&email=you@example.com`) opens a confirmation showing the address. Nothing is saved until you tap Use address.

## Privacy

- Runs offline. The only network use is your mail app sending the email you approve, and the phone's own geocoder naming roads (optional, falls back to coordinates).
- No accounts, no analytics, no backend. Data is stored in a local database on your phone. Settings > Delete all logged data erases it.
- The Feedback row opens this repo's issues page. It never attaches your location or logs. If you add those to an issue, you chose to.

## Battery setup

Tap **Battery setup** in Settings. On Samsung phones also add the app to Settings > Battery > Background usage limits > Never sleeping apps, or One UI can stop it mid-drive.
