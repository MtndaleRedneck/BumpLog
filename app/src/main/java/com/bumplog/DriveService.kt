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
    private var prevFix: Location? = null
    private var finishedCleanly = false
    private var lastFixMs = 0L
    private var lastNotifMs = 0L
    private var lastMoveMs = 0L
    private var lastDrivingMs = 0L // last time the phone was actually moving (above standstill), for auto-off
    private var lastSpeedMph = 0f
    private val clock = object : Runnable {
        override fun run() {
            // Wall-clock tick so the timers still advance when GPS has no fix (indoors, tunnels).
            val now = System.currentTimeMillis()
            if (Live.state.value.running) Prefs.lastAliveMs = now
            val fresh = now - lastFixMs < 30_000L
            passengerTick(if (fresh) lastSpeedMph else 99f) // without a fresh fix we cannot confirm a stop, so Passenger waits
            if (Live.state.value.running && autoOffDue(lastDrivingMs, now, Prefs.autoOffMin)) { autoOff(); return }
            // No GPS indoors: the wall clock closes the trip 15 minutes after the last movement too.
            if (Live.state.value.running && now - lastMoveMs > 15 * 60_000L) rollTrip()
            main.postDelayed(this, 30_000L)
        }
    }
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
                // Power is the master switch: leaving the car does not end logging.
                return START_STICKY
            }
        }
        if (Live.state.value.running) {
            main.removeCallbacks(stopRunnable) // car reconnected
            return START_STICKY
        }
        autoStarted = i?.getBooleanExtra("auto", false) == true
        val now = System.currentTimeMillis()
        // Android restarted the service after a kill: keep the same drive if the phone was still
        // alive in it moments ago, so a long drive is not split in two and its summary stays whole.
        driveId = if (i == null && Prefs.lastAliveMs > 0 && now - Prefs.lastAliveMs < 15 * 60_000L) Prefs.lastDriveId else now
        Prefs.lastDriveId = driveId
        lastMoveMs = now
        lastDrivingMs = now
        Live.update { driveStartState(it.tick, Prefs.passenger, driveId) }
        ServiceCompat.startForeground(this, Notifier.ID_FOREGROUND, Notifier.foreground(this, Live.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startSensors()
        main.removeCallbacks(clock); main.postDelayed(clock, 30_000L)
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

    /** A hit held for the next GPS fix (up to 2 s) so it can be pinned between two fixes. */
    private class PendingHit(val ev: HitEvent, val fix: Location, val prev: Location?, val fallback: Runnable)

    private val pendingHits = ArrayList<PendingHit>()

    /** Worst case before this change: a fix up to 4 s old pinned the hit about 80 m back at 45 mph, about 115 m at 65 mph. */
    private fun onHit(ev: HitEvent, loc: Location) {
        val r = Runnable { settleHit(ev, loc) }
        synchronized(pendingHits) { pendingHits.add(PendingHit(ev, Location(loc), prevFix?.let { Location(it) }, r)) }
        main.postDelayed(r, 2000)
    }

    /** The next fix arrived in time: place the hit between the two fixes by its timestamp, which follows the real path through a turn. */
    private fun interpolateHit(ev: HitEvent, l0: Location, l1: Location) {
        val tH = ev.startMs; val t0 = l0.elapsedRealtimeNanos / 1_000_000; val t1 = l1.elapsedRealtimeNanos / 1_000_000
        val f = if (t1 > t0) ((tH - t0).toDouble() / (t1 - t0)).coerceIn(0.0, 1.0) else 1.0
        var lat = l0.latitude + f * (l1.latitude - l0.latitude)
        var lon = l0.longitude + f * (l1.longitude - l0.longitude)
        var lowConf = false
        // A correction that lands far from both fixes is noise, not the road.
        if (Geo.distance(lat, lon, l0.latitude, l0.longitude) > 50 && Geo.distance(lat, lon, l1.latitude, l1.longitude) > 50) {
            lat = l0.latitude; lon = l0.longitude; lowConf = true
        }
        val speed = mph(l0)
        val heading = when {
            Geo.distance(l0.latitude, l0.longitude, l1.latitude, l1.longitude) > 3 -> Geo.bearing(l0.latitude, l0.longitude, l1.latitude, l1.longitude)
            l0.hasBearing() && speed > 5 -> l0.bearing.toDouble()
            else -> -1.0
        }
        recordHitNow(ev, lat, lon, heading, speed, lowConf)
    }

    /** No next fix within 2 s: walk the last fix forward along its course. A fix older than 2 s, a hard turn just
     * before the hit, or a walk that lands too far away all fall back to the fix as-is, marked low-confidence. */
    private fun settleHit(ev: HitEvent, l0: Location) {
        // Take the entry out under the lock: a fix that arrives after the fallback fired must not record the hit twice.
        // The entry's prev fix is null at a drive's first fix - that must not drop the hit.
        val pending = synchronized(pendingHits) { pendingHits.firstOrNull { it.ev === ev }?.also { pendingHits.remove(it) } } ?: return
        val prev = pending.prev
        val ageS = ((ev.startMs - l0.elapsedRealtimeNanos / 1_000_000) / 1000.0).coerceIn(0.0, 4.0)
        val speed = mph(l0)
        var lat = l0.latitude; var lon = l0.longitude; var lowConf = false
        if (ageS > 2.0) lowConf = true
        else if (l0.hasBearing() && l0.speed > 2) {
            val swing = prev != null && prev.hasBearing() && Geo.angleDiff(l0.bearing.toDouble(), prev.bearing.toDouble()) > 30
            if (swing) lowConf = true
            else {
                val (plat, plon) = Geo.offset(l0.latitude, l0.longitude, l0.bearing.toDouble(), l0.speed * ageS)
                if (Geo.distance(plat, plon, l0.latitude, l0.longitude) <= 50) { lat = plat; lon = plon } else lowConf = true
            }
        }
        val heading = if (l0.hasBearing() && speed > 5) l0.bearing.toDouble() else -1.0
        recordHitNow(ev, lat, lon, heading, speed, lowConf)
    }

    private fun recordHitNow(ev: HitEvent, lat: Double, lon: Double, heading: Double, speed: Float, lowConf: Boolean) {
        val now = System.currentTimeMillis()
        Prefs.lastAliveMs = now
        val guess = Classifier.guess(ev, speed, recentStarts)
        recentStarts.add(ev.startMs); if (recentStarts.size > 12) recentStarts.removeAt(0)
        val hit = Hit(0, 0, driveId, now, lat, lon, heading, speed, ev.peakG, ev.durationMs, ev.peaks, guess, lowConf)
        val spot = db.recordHit(hit, guess)
        passes.values.forEach { it.hit = true }
        Live.update { it.copy(hardestHitG = hardestAfterHit(it.hardestHitG, ev.peakG)) }
        Live.bump()
        if (spot.address.isBlank()) io.execute { lookupAddress(spot) }
        val quiet = guess == DefectType.RUMBLE || spot.type == DefectType.RUMBLE
        if (ev.peakG >= Prefs.severeG && !quiet && spot.review != "DISMISSED" && !(spot.typeUser && spot.review == "CONFIRMED")) {
            Notifier.severe(this, spot, true)
            Sound.play(this, "Bump detected.", true, "hit")
        }
    }

    private fun lookupAddress(s: Spot) {
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(this, Locale.getDefault()).getFromLocation(s.lat, s.lon, 1)?.firstOrNull() ?: return
            val road = a.thoroughfare ?: a.featureName ?: return
            val name = listOfNotNull(road, a.locality).joinToString(", ")
            // Town and county are the phone's guess; the report email says so and the assistant verifies ownership.
            db.setPlace(s.id, name, a.locality ?: "", a.subAdminArea ?: ""); Live.bump()
        } catch (e: Exception) { /* offline or no geocoder: coordinates are shown instead */ }
    }

    override fun onLocationChanged(l: Location) {
        val waiting = synchronized(pendingHits) { if (pendingHits.isEmpty()) null else ArrayList(pendingHits).also { pendingHits.clear() } }
        waiting?.forEach { main.removeCallbacks(it.fallback); interpolateHit(it.ev, it.fix, l) }
        prevFix = last; last = l; lastFixMs = System.currentTimeMillis()
        val sp = mph(l)
        passengerTick(sp)
        val prev = Live.state.value
        Live.update { it.copy(speedMph = sp, gps = true) }
        lastSpeedMph = sp
        if (sp > STANDSTILL_MPH) { lastMoveMs = lastFixMs; lastDrivingMs = lastFixMs }
        if (autoOffDue(lastDrivingMs, lastFixMs, Prefs.autoOffMin)) { autoOff(); return }
        if (lastFixMs - lastMoveMs > 15 * 60_000L) { rollTrip(); return }
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

    /** Passenger mode timer: heads-up 10 minutes before, flip to Driver only once stopped, with Undo afterwards. */
    private fun passengerTick(speedMph: Float) {
        if (!Prefs.passenger) return
        val left = Prefs.passengerLeftMs()
        when (passengerStep(left, Prefs.passengerWarned, speedMph, System.currentTimeMillis() - lastMoveMs)) {
            PassengerStep.NONE -> return
            PassengerStep.WARN -> { Prefs.passengerWarned = true; Notifier.passengerSoon(this, ((left + 59_999) / 60_000).toInt()); return }
            PassengerStep.FLIP -> {}
        }
        Prefs.passenger = false
        Prefs.passengerUndoUntil = System.currentTimeMillis() + 5 * 60_000L
        Live.update { it.copy(paused = false) }
        Notifier.passengerBack(this)
        Notifier.cancelAndRefresh(this)
    }

    /** Parked for 15 minutes: close the trip with its summary, then keep logging as a fresh one. Passenger mode carries over. */
    private fun rollTrip() {
        val hits = db.hitsInDrive(driveId)
        val toFile = db.tripSpots(driveId).size
        if (hits > 0 || toFile > 0) Notifier.driveDone(this, hits, toFile, driveId)
        val dq = db.queuedDamages().size
        if (dq > 0) Notifier.damageWaiting(this, dq)
        // Passenger mode is left alone here: only its own timer (or the user) switches it back to Driver.
        driveId = System.currentTimeMillis(); Prefs.lastDriveId = driveId; lastMoveMs = driveId
        Live.update { tripRolloverState(it, Prefs.passenger, driveId) }
        Notifier.cancelAndRefresh(this)
    }

    /** Not driving for the whole auto-off time: turn Power off and say so, since nothing can turn it back on by itself. */
    private fun autoOff() {
        Prefs.detectionEnabled = false
        Prefs.passenger = false
        finishDrive()
        Notifier.autoOff(this, Prefs.autoOffMin)
    }

    private fun finishDrive() {
        finishedCleanly = true
        if (!Live.state.value.running) { stopSelf(); return }
        main.removeCallbacks(stopRunnable); main.removeCallbacks(clock)
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        val hits = db.hitsInDrive(driveId)
        val toFile = db.tripSpots(driveId).size
        Prefs.passenger = false // the next drive starts un-paused, however it is started
        Live.update { it.copy(running = false, paused = false, speedMph = 0f, gps = false, driveId = driveId, tick = it.tick + 1) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Notifier.removeOverlay(this)
        if (hits > 0 || toFile > 0) Notifier.driveDone(this, hits, toFile, driveId)
        val dq = db.queuedDamages().size
        if (dq > 0) Notifier.damageWaiting(this, dq)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(clock)
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        if (Live.state.value.running) {
            Live.update { it.copy(running = false) }
            // Android killed logging mid-drive with Power still on: say so, because nothing restarts it until the app opens.
            if (!finishedCleanly && Prefs.detectionEnabled) Notifier.loggingStopped(this)
        }
        super.onDestroy()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}

    companion object {
        const val ACTION_START = "com.bumplog.START"
        const val ACTION_STOP = "com.bumplog.STOP_SERVICE"
        const val ACTION_CAR_GONE = "com.bumplog.CAR_GONE"

        fun start(ctx: Context) {
            if (!Prefs.detectionEnabled) return // the Bump Detection switch is a full stop
            ctx.startForegroundService(Intent(ctx, DriveService::class.java).setAction(ACTION_START))
        }
    }
}
