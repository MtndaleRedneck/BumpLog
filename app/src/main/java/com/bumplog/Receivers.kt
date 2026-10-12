package com.bumplog

import android.Manifest
import android.bluetooth.BluetoothClass
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
            STOP -> ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_STOP))
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
    }
}

/** Starts and stops detection when the phone connects to or leaves the car's Bluetooth. */
class BluetoothReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.init(ctx)
        if (!Prefs.autoStart || !Prefs.detectionEnabled) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!isCar(dev)) return
        when (i.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Prefs.passenger = false // a new drive starts un-paused
                try {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_START).putExtra("auto", true))
                } catch (e: Exception) {
                    // Android refused a background start. Ask the user to tap instead.
                    Notifier.channels(ctx)
                    Notifier.carConnected(ctx)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                try { ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_CAR_GONE)) } catch (e: Exception) { }
        }
    }

    @Suppress("MissingPermission")
    private fun isCar(d: BluetoothDevice): Boolean {
        val chosen = Prefs.carAddress
        if (chosen.isNotBlank()) return d.address == chosen
        return try {
            val c = d.bluetoothClass?.deviceClass
            c == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || c == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        } catch (e: Exception) { false }
    }
}
