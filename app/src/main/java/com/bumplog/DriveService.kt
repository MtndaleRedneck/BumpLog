package com.bumplog

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Foreground service: watches the accelerometer and GPS while you drive, logs hits, and warns
 * you before spots you have asked to be warned about.
 */
class DriveService : Service(), SensorEventListener, LocationListener {

    private lateinit var db: Db
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val detector = HitDetector(Prefs.cutoffG)
    private val recentStarts = ArrayList<Long>()
    private var linear = true
    private val grav = FloatArray(3)
    private var driveId = 0L
    private var autoStarted = false
    private var last: Location? = null
    private var lastFixMs = 0L
    private var lastNotifMs = 0L
    private var lastMoveMs = 0L
    private val wave = FloatArray(120)
    private var waveI = 0
    private var waveTick = 0
    private val warned = HashMap<Long, Long>()      // spotId -> time of last far warning
    private val warnedNear = HashMap<Long, Long>()
    private val passes = HashMap<Long, PassState>() // spots we are currently driving past
    private val stopRunnable = Runnable { finishDrive() }

    private class PassState(var minDist: Double, var hit: Boolean, var lastSeen: Long, var fast: Boolean = false)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        db = Db.get(this)
        sm = getSystemService(SensorManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        Notifier.channels(this)
        Sound.init(this)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_STOP -> { finishDrive(); return START_NOT_STICKY }
            ACTION_CAR_GONE -> {
                if (autoStarted) { main.removeCallbacks(stopRunnable); main.postDelayed(stopRunnable, 90_000) }
                return START_STICKY
            }
        }
        if (Live.state.value.running) {
            main.removeCallbacks(stopRunnable) // car reconnected
            return START_STICKY
        }
        autoStarted = i?.getBooleanExtra("auto", false) == true
        driveId = System.currentTimeMillis()
        lastMoveMs = driveId
        Live.update { LiveState(running = true, paused = Prefs.passenger, driveId = driveId, tick = it.tick + 1) }
        ServiceCompat.startForeground(this, Notifier.ID_FOREGROUND, Notifier.foreground(this, Live.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startSensors()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        linear = lin != null
        val s = lin ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) { /* the screen asks for permission first */ }
    }

    override fun onSensorChanged(e: SensorEvent) {
        var x = e.values[0]; var y = e.values[1]; var z = e.values[2]
        if (!linear) { // remove gravity with a low-pass filter
            val a = 0.9f
            grav[0] = a * grav[0] + (1 - a) * x; grav[1] = a * grav[1] + (1 - a) * y; grav[2] = a * grav[2] + (1 - a) * z
            x -= grav[0]; y -= grav[1]; z -= grav[2]
        }
        val g = sqrt(x * x + y * y + z * z) / 9.80665f
        val st = Live.state.value
        wave[waveI] = g; waveI = (waveI + 1) % wave.size
        if (++waveTick % 5 == 0) {
            val w = FloatArray(wave.size) { wave[(waveI + it) % wave.size] }
            Live.update { it.copy(wave = w, lastG = g) }
        }
        if (st.paused || !Prefs.detectionEnabled) { detector.reset(); return }
        val loc = last
        val moving = loc != null && System.currentTimeMillis() - lastFixMs < 4000 && mph(loc) >= Prefs.minSpeedMph
        if (!moving) { detector.reset(); return }
        detector.cutoffG = Prefs.cutoffG
        val ev = detector.onSample(e.timestamp / 1_000_000, g) ?: return
        if (plausibleHit(ev)) onHit(ev, loc!!)
    }

    private fun mph(l: Location) = l.speed * Geo.MPH_PER_MPS.toFloat()

    private fun onHit(ev: HitEvent, loc: Location) {
        val now = System.currentTimeMillis()
        val speed = mph(loc)
        val guess = Classifier.guess(ev, speed, recentStarts)
        recentStarts.add(ev.startMs); if (recentStarts.size > 12) recentStarts.removeAt(0)
        val heading = if (loc.hasBearing() && speed > 5) loc.bearing.toDouble() else -1.0
        val hit = Hit(0, 0, driveId, now, loc.latitude, loc.longitude, heading, speed, ev.peakG, ev.durationMs, ev.peaks, guess)
        val spot = db.recordHit(hit, guess)
        passes.values.forEach { it.hit = true }
        Live.update { it.copy(biggestG = maxOf(it.biggestG, ev.peakG)) }
        Live.bump()
        if (spot.address.isBlank()) io.execute { lookupAddress(spot) }
        val quiet = guess == DefectType.RUMBLE || spot.type == DefectType.RUMBLE
        if (ev.peakG >= Prefs.severeG && !quiet && spot.review != "DISMISSED" && !(spot.typeUser && spot.review == "CONFIRMED")) {
            Notifier.severe(this, spot, true)
            Sound.play(this, "Major bump detected.", true, "hit")
        }
    }

    private fun lookupAddress(s: Spot) {
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(this, Locale.getDefault()).getFromLocation(s.lat, s.lon, 1)?.firstOrNull() ?: return
            val road = a.thoroughfare ?: a.featureName ?: return
            val name = listOfNotNull(road, a.locality).joinToString(", ")
            db.setAddress(s.id, name); Live.bump()
        } catch (e: Exception) { /* offline or no geocoder: coordinates are shown instead */ }
    }

    override fun onLocationChanged(l: Location) {
        last = l; lastFixMs = System.currentTimeMillis()
        val sp = mph(l)
        val prev = Live.state.value
        Live.update { it.copy(speedMph = sp, gps = true) }
        if (sp > 3) lastMoveMs = lastFixMs
        if (lastFixMs - lastMoveMs > 15 * 60_000L) { finishDrive(); return }
        if (!prev.paused && Prefs.detectionEnabled) checkSpots(l, sp)
        if (lastFixMs - lastNotifMs > 5000 || prev.paused != Live.state.value.paused) {
            lastNotifMs = lastFixMs
            Notifier.cancelAndRefresh(this)
        }
    }

    private fun checkSpots(l: Location, speed: Float) {
        if (speed < 3f || !l.hasBearing()) return
        val now = System.currentTimeMillis()
        val far = Prefs.warnFarFt / Geo.FT_PER_M
        val near = Prefs.warnNearFt / Geo.FT_PER_M
        val zone = 24.0 // about 80 ft, so any lane counts
        val spots = db.warnSpots()
        for (s in spots) {
            val d = Geo.distance(l.latitude, l.longitude, s.lat, s.lon)
            // Only spots we travel toward in the same direction as when logged.
            if (s.heading >= 0 && Geo.angleDiff(s.heading, l.bearing.toDouble()) > 50) continue
            val brg = Geo.bearing(l.latitude, l.longitude, s.lat, s.lon)
            val ahead = Geo.angleDiff(brg, l.bearing.toDouble()) < 60
            if (ahead && d <= far && d > near && now - (warned[s.id] ?: 0L) > 120_000) {
                warned[s.id] = now
                Notifier.ahead(this, s, (d * Geo.FT_PER_M).toInt(), false)
                if (Prefs.audioMode == "VOICE" || Prefs.audioMode == "BOTH") Sound.playAhead(this, s, (d * Geo.FT_PER_M).toInt(), false, false)
            }
            if (ahead && d <= near && now - (warnedNear[s.id] ?: 0L) > 60_000) {
                warnedNear[s.id] = now
                Notifier.ahead(this, s, (d * Geo.FT_PER_M).toInt(), true)
                Sound.playAhead(this, s, (d * Geo.FT_PER_M).toInt(), true, true)
            }
            // Track passes through the 80 ft zone so a spot can be offered as fixed after clean passes.
            // Only at speeds where detection is active, so a slow pass can't count as clean.
            if (d <= 60 && speed >= 3f) {
                val p = passes.getOrPut(s.id) { PassState(d, false, now) }
                if (d < p.minDist) p.minDist = d
                if (speed >= Prefs.minSpeedMph) p.fast = true
                p.lastSeen = now
            }
        }
        val it = passes.entries.iterator()
        while (it.hasNext()) {
            val (id, p) = it.next()
            if (now - p.lastSeen > 6000) {
                if (p.minDist <= zone) {
                    // An avoidance: the heads-up fired for this spot and the driver passed it again, hit or no hit.
                    val alerted = maxOf(warned[id] ?: 0L, warnedNear[id] ?: 0L)
                    if (alerted > 0 && now - alerted < 300_000) db.addSave(id)
                    if (p.fast && !p.hit) {
                        db.addCleanPass(id)
                        val sp = db.spot(id)
                        // A spot you reported keeps passing clean: offer to mark it fixed after every 2 clean passes.
                        if (sp != null && sp.reportStatus != "NONE" && sp.review != "FIXED" && sp.cleanPasses >= 2 && sp.cleanPasses % 2 == 0) Notifier.fixedPrompt(this, sp)
                    }
                    Live.bump()
                }
                it.remove()
            }
        }
    }

    private fun finishDrive() {
        if (!Live.state.value.running) { stopSelf(); return }
        main.removeCallbacks(stopRunnable)
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        val hits = db.hitsInDrive(driveId)
        val toFile = db.tripSpots(driveId).size
        Prefs.passenger = false // the next drive starts un-paused, however it is started
        Live.update { it.copy(running = false, paused = false, speedMph = 0f, gps = false, driveId = driveId, tick = it.tick + 1) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Notifier.removeOverlay(this)
        if (hits > 0) Notifier.driveDone(this, hits, toFile, driveId)
        val dq = db.queuedDamages().size
        if (dq > 0) Notifier.damageWaiting(this, dq)
        stopSelf()
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        if (Live.state.value.running) Live.update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}

    companion object {
        const val ACTION_START = "com.bumplog.START"
        const val ACTION_STOP = "com.bumplog.STOP_SERVICE"
        const val ACTION_CAR_GONE = "com.bumplog.CAR_GONE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DriveService::class.java).setAction(ACTION_START))
        }
    }
}
