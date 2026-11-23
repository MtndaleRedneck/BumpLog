package com.bumplog

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Notification and overlay buttons. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.init(ctx)
        val id = i.getLongExtra("spot", 0)
        val db = Db.get(ctx)
        when (i.action) {
            YES -> { db.confirmForTrip(id); android.widget.Toast.makeText(ctx, ConfirmationNotice.flash(db.spot(id)?.ref() ?: "", true), android.widget.Toast.LENGTH_LONG).show() }
            NO -> { db.setReview(id, "DISMISSED"); db.setWarn(id, false) }
            WARN -> db.setWarn(id, true)
            STILL -> { db.resetCleanPasses(id); Notifier.cancel(ctx, "fixed", id.toInt()) }
            PAUSE -> { Prefs.passenger = !Prefs.passenger; Live.update { it.copy(paused = Prefs.passenger) }; if (Live.state.value.running) Notifier.cancelAndRefresh(ctx) }
            EXTEND -> { Prefs.extendPassenger(); Notifier.cancel(ctx, Notifier.ID_PASSENGER) }
            UNDO -> {
                Notifier.cancel(ctx, Notifier.ID_PASSENGER)
                if (System.currentTimeMillis() < Prefs.passengerUndoUntil) { Prefs.passengerUndoUntil = 0L; Prefs.passenger = true; Live.update { it.copy(paused = true) }; if (Live.state.value.running) Notifier.cancelAndRefresh(ctx) }
            }
            STOP -> { Prefs.detectionEnabled = false; Prefs.passenger = false; Notifier.cancel(ctx, Notifier.ID_CAR); ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_STOP)) }
            // This receiver is not exported: only the app's own PendingIntents reach these
            // two, so they - not intent extras on the exported launcher activity - carry
            // the side effects. State goes through Prefs; the app opens without commands.
            RESTART -> {
                Prefs.detectionEnabled = true
                Prefs.restartWanted = true // MainActivity applies its usual start + permission flow
                Notifier.cancel(ctx, Notifier.ID_CAR)
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            SENDTRIP -> {
                val driveId = i.getLongExtra("drive", 0L)
                if (driveId != 0L) {
                    if (!Prefs.setupDone) {
                        // Assistant setup happens once before the first report; the trip waits for it.
                        Prefs.pendingTrip = driveId
                    } else {
                        val list = db.tripSpots(driveId)
                        if (list.isEmpty()) android.widget.Toast.makeText(ctx, "No confirmed reports left to send.", android.widget.Toast.LENGTH_LONG).show()
                        else if (handOffMail(ctx, Reports.reportsMail(ctx, list))) {
                            Prefs.askSentFor = list.joinToString(",") { it.id.toString() }; Prefs.askSentKind = "trip"
                        }
                    }
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
        }
        if (id != 0L) {
            Notifier.cancel(ctx, "hit", id.toInt())
            Notifier.removeOverlay(ctx)
        }
        Live.bump()
    }

    companion object {
        const val YES = "com.bumplog.YES"
        const val NO = "com.bumplog.NO"
        const val WARN = "com.bumplog.WARN"
        const val PAUSE = "com.bumplog.PAUSE"
        const val STOP = "com.bumplog.STOP"
        const val STILL = "com.bumplog.STILL"
        const val EXTEND = "com.bumplog.EXTEND"
        const val UNDO = "com.bumplog.UNDO"
        const val RESTART = "com.bumplog.RESTART"
        const val SENDTRIP = "com.bumplog.SENDTRIP"
    }
}

/** Offers to start or stop logging when the phone connects to or leaves the car device chosen in Settings. */
class BluetoothReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.init(ctx)
        if (!Prefs.autoStart) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!isCar(dev)) return
        Notifier.channels(ctx)
        when (i.action) {
            // Only the one device chosen in Settings counts. Both cases only offer; the tap is what changes anything.
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!(Prefs.detectionEnabled && Live.state.value.running)) Notifier.carConnected(ctx)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (Live.state.value.running) Notifier.carGone(ctx)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun isCar(d: BluetoothDevice): Boolean {
        val chosen = Prefs.carAddress
        return chosen.isNotBlank() && d.address == chosen
    }
}

/** After a phone restart with Power on, says logging is paused and offers the same tap-to-start as the car notice. Never starts by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        Prefs.init(ctx)
        if (!Prefs.detectionEnabled) return
        Notifier.channels(ctx)
        Notifier.loggingPaused(ctx)
    }
}
