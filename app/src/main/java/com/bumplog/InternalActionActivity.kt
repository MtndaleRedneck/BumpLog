package com.bumplog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Target of the two drive-stop notification actions that change state (restart logging, send
 * the trip). It is a NON-EXPORTED activity, not a receiver: Android 12+ blocks notification
 * taps that first hit a broadcast receiver (the "notification trampoline" restriction), which
 * would silently strand the tap. A PendingIntent aimed straight at an activity fires directly,
 * and exported="false" keeps the side effects unreachable for any other app - the same boundary
 * the receiver gave, through a route that still works. The screen is NoDisplay and finishes at
 * once; state goes through Prefs and the app opens plainly, without command extras.
 */
class InternalActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val db = Db.get(this)
        // MainActivity first, plain: the normal screen sits behind whatever follows, and a mail
        // composer (when one opens) lands on top of it. No command extras are forwarded.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        when (intent.getStringExtra("do")) {
            "restart" -> {
                Prefs.detectionEnabled = true
                Prefs.restartWanted = true // MainActivity applies its usual start + permission flow
                Notifier.cancel(this, Notifier.ID_CAR)
            }
            "sendtrip" -> {
                val driveId = intent.getLongExtra("drive", 0L)
                if (driveId != 0L) {
                    if (!Prefs.setupDone) {
                        // Assistant setup happens once before the first report; the trip waits for it.
                        Prefs.pendingTrip = driveId
                    } else {
                        val list = db.tripSpots(driveId)
                        if (list.isEmpty()) Toast.makeText(this, "No confirmed reports left to send.", Toast.LENGTH_LONG).show()
                        else if (handOffMail(this, Reports.reportsMail(this, list))) {
                            PendingAsk.set(list.joinToString(",") { it.id.toString() }, "trip")
                        }
                    }
                }
            }
        }
        Live.bump()
        finish()
    }

    companion object {
        fun restartIntent(ctx: Context) = Intent(ctx, InternalActionActivity::class.java).putExtra("do", "restart")
        fun sendTripIntent(ctx: Context, driveId: Long) = Intent(ctx, InternalActionActivity::class.java).putExtra("do", "sendtrip").putExtra("drive", driveId)
    }
}
