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
            YES -> { db.setReview(id, "CONFIRMED"); db.setWarn(id, true) }
            NO -> { db.setReview(id, "DISMISSED"); db.setWarn(id, false) }
            WARN -> db.setWarn(id, true)
            STILL -> { db.resetCleanPasses(id); Notifier.cancel(ctx, 3000 + id.toInt()) }
            PAUSE -> { Prefs.passenger = !Prefs.passenger; Live.update { it.copy(paused = Prefs.passenger) } }
            EXTEND -> { Prefs.extendPassenger(); Notifier.cancel(ctx, Notifier.ID_PASSENGER) }
            UNDO -> {
                Notifier.cancel(ctx, Notifier.ID_PASSENGER)
                if (System.currentTimeMillis() < Prefs.passengerUndoUntil) { Prefs.passengerUndoUntil = 0L; Prefs.passenger = true; Live.update { it.copy(paused = true) } }
            }
            STOP -> { Prefs.detectionEnabled = false; Prefs.passenger = false; Notifier.cancel(ctx, Notifier.ID_CAR); ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_STOP)) }
        }
        if (id != 0L) {
            Notifier.cancel(ctx, 1000 + id.toInt())
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
