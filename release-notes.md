BumpLog 1.14.1

- The setup email now asks your assistant to check with you first: it should ask "Did you set up BumpLog?" the way it normally reaches you, and leave BumpLog emails alone until you say yes. The scripted test reply is gone; the setup email is no longer labeled as a test.
- The assistant guide has a "Before you act" section with the same rule, and the note at the end of every report email opens with it. Version tags drop from the guide title and the email headers; the version stays internal.
- Setup state survives the trip to your mail app: every setup send lands on the same "Your assistant will check with you" step, and Done never waits on a reply. Settings can resend the setup email or copy the guide at any time.
- Settings: the Test email and Test alert buttons are gone (the Testing card still simulates alerts).
- Stats shows one full-width Reports sent card.
- A standalone CSV import now moves the report counter by the same rule as a full backup restore.

Android 12+. Installs over 1.14.0; all data and the update check carry over.
