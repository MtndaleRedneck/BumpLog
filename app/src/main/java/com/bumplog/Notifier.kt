package com.bumplog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

object Notifier {
    /** The ongoing notification's channel. A channel keeps the importance it was created with, so the lower importance needs a new id. */
    const val CH_STATUS = "persistent"
    private const val CH_STATUS_OLD = "status"
    const val CH_ALERT = "alerts"
    const val ID_FOREGROUND = 1
    const val ID_DRIVE_DONE = 2
    const val ID_CAR = 3
    const val ID_TEST = 4
    const val ID_DAMAGE = 5
    const val ID_AUTO_OFF = 6
    const val ID_PASSENGER = 7
    private val main = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    /** Opens Android's own settings for the persistent notification channel. */
    fun openPersistentSettings(ctx: Context) {
        channels(ctx)
        val i = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_STATUS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CH_STATUS_OLD)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Persistent notification", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while BumpLog is watching the road"
            setShowBadge(false)
        })
        // Heads-up channel. Sound is played by the app itself so tone and voice can be chosen.
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Road defect alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pop-up alerts for severe hits and defects ahead"
            setSound(null, null)
            enableVibration(true)
        })
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openApp(ctx: Context, spotId: Long = 0, screen: String? = null, start: Boolean = false): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("spot", spotId); screen?.let { putExtra("screen", it) }; putExtra("start", start)
        }
        return PendingIntent.getActivity(ctx, (spotId * 7 + (screen?.hashCode() ?: 0) + (if (start) 13 else 0)).toInt(), i, flags())
    }

    fun logIt(ctx: Context, spotId: Long): PendingIntent =
        PendingIntent.getActivity(ctx, 5000 + spotId.toInt(), logItIntent(ctx, spotId), flags())

    fun logItIntent(ctx: Context, spotId: Long) =
        Intent(ctx, LogItActivity::class.java).putExtra("spot", spotId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun action(ctx: Context, action: String, spotId: Long = 0): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("spot", spotId)
        return PendingIntent.getBroadcast(ctx, (action.hashCode() xor spotId.toInt()), i, flags())
    }

    fun foreground(ctx: Context, state: LiveState): Notification {
        val text = when {
            state.paused -> { val m = (Prefs.passengerLeftMs() + 59_999) / 60_000; "Paused (passenger). Back to Driver in ${m / 60}h ${m % 60}m." }
            !state.gps -> "Detecting. Waiting for GPS."
            else -> "Detecting. %d mph".format(state.speedMph.toInt())
        }
        val b = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("BumpLog").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(ctx))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        b.addAction(0, if (state.paused) "Resume" else "Passenger: pause", action(ctx, ActionReceiver.PAUSE))
        b.addAction(0, "Stop", action(ctx, ActionReceiver.STOP))
        return b.build()
    }

    fun cancelAndRefresh(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_FOREGROUND, foreground(ctx, Live.state.value))
    }

    fun severe(ctx: Context, s: Spot, moving: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Bump detected.")
            .setContentText("%.2fg near ${s.title}".format(s.maxG))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(openApp(ctx, s.id)).setColor(0xFFFF5228.toInt())
        if (Prefs.hitTimeoutSec > 0) b.setTimeoutAfter(Prefs.hitTimeoutSec * 1000L)
        val showButtons = !(Prefs.lockButtons && moving)
        if (showButtons) {
            b.addAction(0, "Log it", logIt(ctx, s.id))
            b.addAction(0, "Ignore", action(ctx, ActionReceiver.NO, s.id))
            b.addAction(0, "Warn me here forever", action(ctx, ActionReceiver.WARN, s.id))
        }
        nm.notify(1000 + s.id.toInt(), b.build())
        if (Prefs.overlay && showButtons) overlay(ctx, s)
    }

    /** Names the defect plainly once the user has confirmed its type; before that it stays generic. */
    fun aheadName(s: Spot): String {
        if (!s.typeUser) return "Road defect ahead"
        val what = s.type.spoken
        val sev = if (s.severity == "MINOR") "Minor" else "Major"
        return "$sev $what ahead"
    }

    fun ahead(ctx: Context, s: Spot, feet: Int, final: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val dist = if (final) "%d ft".format(feet / 10 * 10) else if (feet >= 1000) "about a quarter mile" else "%d ft".format(feet / 10 * 10)
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("${aheadName(s)} - $dist")
            .setContentText("${s.type.label} on ${s.title}")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTimeoutAfter(15000).setAutoCancel(true).setContentIntent(openApp(ctx, s.id)).setColor(0xFFFF5228.toInt())
        nm.notify(2000 + s.id.toInt(), b.build())
    }

    /** Offers to mark a reported spot fixed after clean passes. Mark fixed opens the app for the celebration. */
    fun fixedPrompt(ctx: Context, s: Spot) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val what = if (s.typeUser) s.type.spoken else "road defect"
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Reported spot may be fixed")
            .setContentText("No impact detected in a previously reported area, mark $what fixed?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("No impact detected in a previously reported area, mark $what fixed? (${s.title})"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true).setContentIntent(openApp(ctx, s.id)).setColor(0xFFFF5228.toInt())
        b.addAction(0, "Mark fixed", openApp(ctx, s.id, "markfixed"))
        b.addAction(0, "Still there", action(ctx, ActionReceiver.STILL, s.id))
        nm.notify(3000 + s.id.toInt(), b.build())
    }

    fun driveDone(ctx: Context, hits: Int, toFile: Int, driveId: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val b = NotificationCompat.Builder(ctx, CH_ALERT).setSmallIcon(R.drawable.ic_stat).setContentTitle("Drive complete").setAutoCancel(true)
        if (toFile > 0) {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("sendtrip", driveId)
            val what = if (toFile == 1) "1 report" else "$toFile reports"
            b.setContentText("$what marked to file. Tap to open one email with " + (if (toFile == 1) "it." else "all of them."))
                .setContentIntent(PendingIntent.getActivity(ctx, 6000 + driveId.toInt(), i, flags()))
        } else b.setContentText("$hits saved. Nothing was marked to file.").setContentIntent(openApp(ctx, 0, "review"))
        nm.notify(ID_DRIVE_DONE, b.build())
    }

    fun damageWaiting(ctx: Context, count: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(ID_DAMAGE, NotificationCompat.Builder(ctx, CH_ALERT).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (count == 1) "1 damage report waiting" else "$count damage reports waiting")
            .setContentText("Tap to add photos and send.").setAutoCancel(true).setContentIntent(openApp(ctx, 0, "damagequeue")).build())
    }

    fun carConnected(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(ID_CAR, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Car connected")
            .setContentText("Tap to start logging this drive.")
            .setAutoCancel(true).setContentIntent(openApp(ctx, 0, null, true)).build())
    }

    fun passengerSoon(ctx: Context, minutes: Int) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_PASSENGER, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Passenger mode ends soon")
            .setContentText("Back to Driver in about $minutes min, once you are stopped.")
            .addAction(0, "Extend 3 hours", action(ctx, ActionReceiver.EXTEND))
            .setAutoCancel(true).setContentIntent(openApp(ctx)).build())
    }

    fun passengerBack(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_PASSENGER, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Back to Driver")
            .setContentText("BumpLog is logging hits again. Tap Undo to stay in Passenger mode.")
            .addAction(0, "Undo", action(ctx, ActionReceiver.UNDO))
            .setAutoCancel(true).setTimeoutAfter(5 * 60_000L).setContentIntent(openApp(ctx)).build())
    }

    fun carGone(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_CAR, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Car disconnected")
            .setContentText("Stop logging? It keeps running until you stop it.")
            .addAction(0, "Stop logging", action(ctx, ActionReceiver.STOP))
            .setAutoCancel(true).setContentIntent(openApp(ctx)).build())
    }

    fun autoOff(ctx: Context, minutes: Int) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_AUTO_OFF, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("BumpLog turned off")
            .setContentText("No driving for $minutes minutes. Tap to turn it back on.")
            .setAutoCancel(true).setContentIntent(openApp(ctx, 0, null, true)).build())
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)

    fun testAlert(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(ID_TEST, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("TEST alert - road defect ahead")
            .setContentText("This is only a test. Nothing was logged.")
            .setPriority(NotificationCompat.PRIORITY_MAX).setTimeoutAfter(8000).setAutoCancel(true).build())
        Sound.play(ctx, "Road defect ahead", true)
    }

    // ---- experimental overlay (off by default) ----

    /** Shows the overlay pop-up with sample data so the user can see what it looks like. */
    fun previewOverlay(ctx: Context): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        val s = Spot(-1, "BL-PREVIEW", 0.0, 0.0, -1.0, DefectType.values().first(), false, 1.34f, 1, 40f, 0, 0, 0, "PENDING", false, "NONE", 0, "Sample road", 0)
        overlay(ctx, s, preview = true)
        return true
    }

    private fun overlay(ctx: Context, s: Spot, preview: Boolean = false) {
        if (!Settings.canDrawOverlays(ctx)) return
        main.post {
            try {
                removeOverlay(ctx)
                val app = ctx.applicationContext
                val wm = app.getSystemService(WindowManager::class.java)
                val dp = app.resources.displayMetrics.density
                val accentArgb = Swatches[Prefs.accent.coerceIn(0, Swatches.lastIndex)].toArgb()
                val box = LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((18 * dp).toInt(), (14 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
                    background = GradientDrawable().apply { setColor(0xFF1C1C20.toInt()); cornerRadius = 20 * dp; setStroke((2 * dp).toInt(), accentArgb) }
                }
                box.addView(TextView(app).apply {
                    text = (if (preview) "PREVIEW  " else "") + "MAJOR BUMP DETECTED."; setTextColor(Color.WHITE)
                    textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                })
                val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                fun btn(label: String, act: String) = Button(app).apply {
                    text = label; isAllCaps = false; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(if (act == ActionReceiver.YES) accentArgb else 0xFF26262C.toInt()); cornerRadius = 14 * dp }
                    setOnClickListener {
                        if (!preview && act == ActionReceiver.YES) { try { app.startActivity(logItIntent(app, s.id)) } catch (e: Exception) { } }
                        else if (!preview) app.sendBroadcast(Intent(app, ActionReceiver::class.java).setAction(act).putExtra("spot", s.id).setPackage(app.packageName))
                        removeOverlay(app)
                    }
                }
                row.addView(btn("Log it", ActionReceiver.YES), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(btn("Ignore", ActionReceiver.NO), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(btn("Warn", ActionReceiver.WARN), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(row)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP; y = (60 * dp).toInt(); x = 0 }
                wm.addView(box, lp)
                overlayView = box
                if (Prefs.hitTimeoutSec > 0) main.postDelayed({ removeOverlay(app) }, Prefs.hitTimeoutSec * 1000L)
            } catch (e: Exception) { }
        }
    }

    fun removeOverlay(ctx: Context) {
        val v = overlayView ?: return
        overlayView = null
        try { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
    }
}
