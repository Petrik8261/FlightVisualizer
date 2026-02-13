package sk.dubrava.flightvisualizer.ui.main

import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.core.FlightHelper
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MAP_DEBUG"
    }

    private var googleMap: GoogleMap? = null
    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

    private lateinit var vehicleType: String
    private lateinit var flightHelper: FlightHelper

    private lateinit var playbackSeekBar: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnStepBack: Button
    private lateinit var btnStepForward: Button

    private lateinit var attitudeView: AttitudeHudView

    private var lastFrameNanos: Long = 0L
    private var playbackTimeSec = 0.0          // “playhead” v sekundách (od začiatku)
    private var segmentIndex = 0               // medzi segmentIndex a segmentIndex+1 interpolujeme
    private var segmentT0Sec = 0.0             // začiatok segmentu v sekundách (kumulatívne)
    private var segmentDurSec = 0.2            // dĺžka segmentu v sekundách
    private val baseTimeScale = 1.6  // skús 1.4 až 2.2 podľa pocitu
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private var isFrameCallbackPosted = false
    private var camLat = Double.NaN
    private var camLon = Double.NaN

    private var lastSeekbarUpdateMs = 0L
    private var lastSeekbarValue = -1



    private var isPlaying = false
    private var currentIndex = 0
    private var playbackSpeed = 2.0


    private var flightMarker: Marker? = null

    // smoothing
    private var lastRoll = 0.0
    private var lastPitch = 0.0
    private var lastHeading = 0.0
    private var lastYaw = 0.0

    private var lastAltitudeM = Double.NaN

    // keď odchádzame, už nič neupdatuje UI/mapu
    private var isExiting = false

    private val framesCount: Int
        get() = min(routeLatLng.size, flightPoints.size)

    private val lastFrameIndex: Int
        get() = (framesCount - 1).coerceAtLeast(0)

    private fun hasFrames(): Boolean = framesCount > 0

    // -----------------------------
    // Helpers
    // -----------------------------

    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    private fun smoothAngle(prev: Double, new: Double, alpha: Double = 0.15): Double {
        var diff = new - prev
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0
        return prev + diff * alpha
    }

    private fun headingDegrees(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    private fun renderFrameSafe(index: Int) {
        if (isExiting) return
        if (!hasFrames()) return

        val i = index.coerceIn(0, lastFrameIndex)
        currentIndex = i

        val point = routeLatLng[i]

        // marker
        flightMarker?.position = point

        // kamera – plynulo len pri play
        val delay = (200L / playbackSpeed).toLong().coerceAtLeast(40L)
        val animMs = (delay - 10).coerceAtLeast(40).toInt()

        if (isPlaying) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLng(point), animMs, null)
        } else {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLng(point))
        }

        updateUiForIndexSafe(i)

        if (playbackSeekBar.progress != i) {
            playbackSeekBar.progress = i
        }
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun lerpAngleDeg(aDeg: Double, bDeg: Double, t: Double): Double {
        var diff = bDeg - aDeg
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0
        return norm360(aDeg + diff * t)
    }

    private fun segDurationSec(i: Int): Double {
        val fp = flightPoints.getOrNull(i) ?: return 0.2
        val dt = fp.dtSec
        return if (dt.isFinite() && dt > 0.0) dt else 0.2
    }

    private fun totalDurationSec(): Double {
        val lastSeg = (framesCount - 2).coerceAtLeast(0)
        var sum = 0.0
        for (i in 0..lastSeg) sum += segDurationSec(i)
        return sum
    }

    private fun seekToTimeSec(tSec: Double) {
        if (framesCount < 2) {
            playbackTimeSec = 0.0
            segmentIndex = 0
            segmentT0Sec = 0.0
            segmentDurSec = 0.2
            return
        }

        playbackTimeSec = tSec.coerceIn(0.0, totalDurationSec())

        var i = 0
        var acc = 0.0
        val lastSeg = (framesCount - 2).coerceAtLeast(0)

        while (i < lastSeg) {
            val d = segDurationSec(i)
            if (playbackTimeSec < acc + d) break
            acc += d
            i++
        }

        segmentIndex = i
        segmentT0Sec = acc
        segmentDurSec = segDurationSec(i).coerceAtLeast(0.02)
    }

    // -----------------------------
    // Lifecycle
    // -----------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""


        flightHelper = FlightHelper(contentResolver)
        vehicleType = intent.getStringExtra(AppNav.EXTRA_VEHICLE_TYPE) ?: AppNav.VEHICLE_PLANE

        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnStepBack = findViewById(R.id.btnStepBack)
        btnStepForward = findViewById(R.id.btnStepForward)

        attitudeView = findViewById(R.id.attitudeView)


        // systémový BACK – nech ide cez safeExit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                safeExitToPrevious()
            }
        })

        btnPlay.setOnClickListener { if (isPlaying) pausePlayback() else startPlayback() }
        btnStop.setOnClickListener { resetPlayback() }

        btnStepBack.setOnClickListener {
            pausePlayback()
            if (!hasFrames()) return@setOnClickListener
            renderFrameSafe((currentIndex - 1).coerceAtLeast(0))
        }

        btnStepForward.setOnClickListener {
            pausePlayback()
            if (!hasFrames()) return@setOnClickListener
            renderFrameSafe((currentIndex + 1).coerceAtMost(lastFrameIndex))
        }

        btnSpeed.setOnClickListener { cyclePlaybackSpeed() }


        if (!checkGooglePlayServices()) return

        val mapFragment = supportFragmentManager.findFragmentByTag("MAP") as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mapContainer, it, "MAP")
                    .commitNow()
            }

        mapFragment.getMapAsync(this)
    }

    override fun onStop() {
        pausePlayback()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // -----------------------------
    // Menu
    // -----------------------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_back -> {
                safeExitToPrevious()
                true
            }
            R.id.action_home -> {
                safeGoHomeToImport()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun beginExitMode() {
        if (isExiting) return
        isExiting = true

        pausePlayback()

        googleMap = null
        flightMarker = null
    }

    private fun safeExitToPrevious() {
        beginExitMode()
        window.decorView.post {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun safeGoHomeToImport() {
        beginExitMode()

        val i = android.content.Intent(
            this,
            sk.dubrava.flightvisualizer.ui.importdata.ImportActivity::class.java
        ).apply {
            putExtra(AppNav.EXTRA_VEHICLE_TYPE, vehicleType)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        window.decorView.post {
            startActivity(i)
            finish()
            overridePendingTransition(0, 0)
        }
    }
    private fun scaledMarker(resId: Int, widthDp: Int, heightDp: Int) =
        BitmapDescriptorFactory.fromBitmap(
            android.graphics.Bitmap.createScaledBitmap(
                android.graphics.BitmapFactory.decodeResource(resources, resId),
                (widthDp * resources.displayMetrics.density).toInt(),
                (heightDp * resources.displayMetrics.density).toInt(),
                true
            )
        )
    private fun advancePlayhead(frameTimeNanos: Long) {
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            return
        }

        val dt = ((frameTimeNanos - lastFrameNanos).coerceAtMost(50_000_000L)) / 1_000_000_000.0
        lastFrameNanos = frameTimeNanos

        // posuň playhead
        playbackTimeSec += dt * playbackSpeed * baseTimeScale

        // koniec?
        val total = totalDurationSec()
        if (playbackTimeSec >= total) {
            playbackTimeSec = total
            isPlaying = false
            btnPlay.text = "Play"
            return
        }

        // ak playhead preskočil viac segmentov, dobehni segmentIndex
        while (segmentIndex < framesCount - 2 && playbackTimeSec >= segmentT0Sec + segmentDurSec) {
            segmentT0Sec += segmentDurSec
            segmentIndex++
            segmentDurSec = segDurationSec(segmentIndex).coerceAtLeast(0.02)
        }
    }



    // -----------------------------
    // Map
    // -----------------------------
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE

        val uriStr = intent.getStringExtra(AppNav.EXTRA_FILE_URI)
        if (uriStr.isNullOrBlank()) {
            Toast.makeText(this, "Chýba súbor (URI).", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uri = Uri.parse(uriStr)
        flightPoints = flightHelper.loadFlight(uri)

        if (flightPoints.size < 2) {
            Toast.makeText(this, "Nepodarilo sa načítať dostatok bodov zo súboru.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        routeLatLng = flightHelper.buildRoute(flightPoints)
        if (!hasFrames()) {
            Toast.makeText(this, "Nedá sa prehrávať – nesedia dáta.", Toast.LENGTH_LONG).show()
            return
        }

        playbackSeekBar.max = lastFrameIndex
        playbackSeekBar.progress = 0
        currentIndex = 0

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (!hasFrames()) return

                pausePlayback()

                val i = progress.coerceIn(0, lastFrameIndex)
                renderFrameSafe(i)

                // zosúladiť playhead čas s indexom (aby play pokračoval plynule odtiaľ)
                var t0 = 0.0
                val targetSeg = i.coerceIn(0, (framesCount - 2).coerceAtLeast(0))
                for (k in 0 until targetSeg) t0 += segDurationSec(k)
                seekToTimeSec(t0)
            }


            override fun onStartTrackingTouch(seekBar: SeekBar?) = pausePlayback()
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        drawRoute(routeLatLng)

        val startPoint = routeLatLng.first()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))

        // marker (flat + rotation)
        flightMarker = googleMap?.addMarker(
            MarkerOptions()
                .position(routeLatLng.first())
                .icon(scaledMarker(R.drawable.aircraft_top, 56, 56)) // <- menšie
                .anchor(0.5f, 0.5f)
                .flat(true)
        )



        showFrame(0)
    }

    // -----------------------------
    // HUD + “ikonka lietadla” update
    // -----------------------------
    private fun updateUiForIndexSafe(index: Int) {
        if (isExiting) return
        if (!hasFrames()) return
        if (index !in 0..lastFrameIndex) return

        val fp = flightPoints[index]
        val altM = fp.altitude

        if (index == 0 || lastAltitudeM.isNaN()) {
            lastAltitudeM = altM
        }

        // --- RAW (ošetri NaN/Inf) ---
        val rawHeading = fp.heading.takeIf { it.isFinite() } ?: 0.0
        val rawPitch   = fp.pitch.takeIf { it.isFinite() } ?: 0.0
        val rawRoll    = fp.roll.takeIf { it.isFinite() } ?: 0.0

        if (index == 0) {
            lastHeading = norm360(rawHeading)
            lastPitch = rawPitch
            lastRoll = rawRoll
            lastYaw = norm360(rawHeading)
        }

        // --- Smooth ---
        val headingSmoothed = smoothAngle(lastHeading, rawHeading, 0.15)
        val pitchSmoothed   = smoothAngle(lastPitch, rawPitch, 0.15)
        val rollSmoothed    = smoothAngle(lastRoll, rawRoll, 0.15)

        val headingNorm = norm360(headingSmoothed)
        lastHeading = headingNorm
        lastPitch = pitchSmoothed
        lastRoll = rollSmoothed

        // --- Yaw/course (prefer yawDeg, fallback GPS bearing) ---
        val yawCourse = fp.yawDeg?.takeIf { it.isFinite() } ?: if (index > 0) {
            val prev = flightPoints[index - 1]
            headingDegrees(
                LatLng(prev.latitude, prev.longitude),
                LatLng(fp.latitude, fp.longitude)
            )
        } else {
            norm360(rawHeading)
        }

        val yawSmoothed = smoothAngle(lastYaw, yawCourse, 0.20)
        val yawNorm = norm360(yawSmoothed)
        lastYaw = yawNorm

        // =========================
        // 1) AttitudeHudView (grafika)
        // =========================
        // Tu posielame FYZIKÁLNE znamienka: +pitch stúpa, +roll vpravo.
        attitudeView.pitchDeg = pitchSmoothed.toFloat()
        attitudeView.rollDeg = rollSmoothed.toFloat()
        attitudeView.headingDeg = yawNorm.toFloat()

        val spd = fp.speedKmh?.takeIf { it.isFinite() }
        attitudeView.speedKts = spd?.toFloat()
        attitudeView.altitudeFt = altM.toFloat()

        // =========================
        // 2) Texty (zatiaľ nech ostanú, potom ich nahradíme “tapes”)
        // =========================



       val vs = fp.vsMps?.takeIf { it.isFinite() }


        // =========================
        // 3) Map marker yaw
        // =========================
        flightMarker?.rotation = yawNorm.toFloat()

        // =========================
        // 4) Pseudo-3D ikonky (2D ImageView)
        // =========================
        // Nastavenie "hĺbky" (stačí raz, ale nevadí aj tu)

    }



    // -----------------------------
    // Frame update
    // -----------------------------
    private fun showFrame(index: Int) = renderFrameSafe(index)

    // -----------------------------
    // Playback
    // -----------------------------
    private fun startPlayback() {
        if (isExiting || !hasFrames() || isPlaying) return

        // zosúladiť playhead podľa aktuálneho seekbaru
        val i = playbackSeekBar.progress.coerceIn(0, lastFrameIndex)
        var t0 = 0.0
        val targetSeg = i.coerceIn(0, (framesCount - 2).coerceAtLeast(0))
        for (k in 0 until targetSeg) t0 += segDurationSec(k)
        seekToTimeSec(t0)

        isPlaying = true
        btnPlay.text = "Pause"
        lastFrameNanos = 0L

        if (!isFrameCallbackPosted) {
            choreographer.postFrameCallback(frameCallback)
            isFrameCallbackPosted = true
        }
    }



    private fun pausePlayback() {
        isPlaying = false
        btnPlay.text = "Play"
        choreographer.removeFrameCallback(frameCallback)
        isFrameCallbackPosted = false
        lastFrameNanos = 0L
    }



    private fun resetPlayback() {
        pausePlayback()
        if (!hasFrames()) return

        currentIndex = 0
        playbackSeekBar.progress = 0

        val start = routeLatLng.first()
        flightMarker?.position = start
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 15f))

        seekToTimeSec(0.0)
        lastFrameNanos = 0L
        segmentIndex = 0
        segmentT0Sec = 0.0
        segmentDurSec = segDurationSec(0).coerceAtLeast(0.02)


        renderFrameSafe(0)
    }

    private fun cyclePlaybackSpeed() {
        playbackSpeed = when (playbackSpeed) {
            0.5 -> 1.0
            1.0 -> 2.0
            2.0 -> 4.0
            else -> 0.5
        }

        val label = when (playbackSpeed) {
            0.5 -> "0.5X"
            1.0 -> "1X"
            2.0 -> "2X"
            4.0 -> "4X"
            else -> "${playbackSpeed}X"
        }

        btnSpeed.text = label
        Toast.makeText(this, "Rýchlosť prehrávania: $label", Toast.LENGTH_SHORT).show()
    }


    // -----------------------------
    // Polyline
    // -----------------------------
    private fun drawRoute(points: List<LatLng>) {
        val map = googleMap ?: return
        if (points.size < 2) return

        val polylineOptions = PolylineOptions()
            .addAll(points)
            .width(6f)
            .color(0xFFFFC107.toInt())

        map.addPolyline(polylineOptions)
    }

    private fun renderInterpolated(frameTimeNanos: Long) {
        if (isExiting) return
        if (framesCount < 2) return

        val i = segmentIndex.coerceIn(0, framesCount - 2)
        val a = flightPoints[i]
        val b = flightPoints[i + 1]

        val ta = segmentT0Sec
        val tb = segmentT0Sec + segmentDurSec
        val t = if (tb > ta) ((playbackTimeSec - ta) / (tb - ta)) else 0.0
        val tt = t.coerceIn(0.0, 1.0)

        // --- Interpolácia polohy ---
        val lat = lerp(a.latitude, b.latitude, tt)
        val lon = lerp(a.longitude, b.longitude, tt)
        val pos = LatLng(lat, lon)
        flightMarker?.position = pos

        if (isPlaying) {
            updateCameraSmooth(pos)
        }



        // --- Interpolácia orientácie (heading/yaw/pitch/roll) ---
        val pitch = lerp(a.pitch, b.pitch, tt)
        val roll  = lerp(a.roll,  b.roll,  tt)

        // yaw preferuj yawDeg, inak heading, inak GPS bearing
        val yawA = (a.yawDeg?.takeIf { it.isFinite() } ?: a.heading.takeIf { it.isFinite() })
            ?: headingDegrees(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude))

        val yawB = (b.yawDeg?.takeIf { it.isFinite() } ?: b.heading.takeIf { it.isFinite() })
            ?: headingDegrees(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude))

        val yaw = lerpAngleDeg(yawA, yawB, tt)

        // HUD
        attitudeView.pitchDeg = pitch.toFloat()
        attitudeView.rollDeg = roll.toFloat()
        attitudeView.headingDeg = yaw.toFloat()

        // speed/alt/vs (lerp keď existuje, inak N/A)
        val spd = if (a.speedKmh != null && b.speedKmh != null) lerp(a.speedKmh, b.speedKmh, tt) else a.speedKmh
        attitudeView.speedKts = spd?.takeIf { it.isFinite() }?.toFloat()

        val alt = lerp(a.altitude, b.altitude, tt)
        attitudeView.altitudeFt = alt.toFloat()

        // VS: ak máš vsMps (m/s), premeň na fpm (1 m/s = 196.850394 fpm)
        val vs = if (a.vsMps != null && b.vsMps != null) lerp(a.vsMps, b.vsMps, tt) else a.vsMps
        val vsFpmLocal = vs?.takeIf { it.isFinite() }?.let { (it * 196.850394).toFloat() }
        attitudeView.vsFpm = vsFpmLocal

        // map marker yaw
        flightMarker?.rotation = yaw.toFloat()

        val nowMs = android.os.SystemClock.uptimeMillis()
        if (i != lastSeekbarValue && (nowMs - lastSeekbarUpdateMs) >= 80L) { // 12.5 Hz
            lastSeekbarValue = i
            lastSeekbarUpdateMs = nowMs
            // ešte lepšie (ak máš Material/AndroidX): seekBar.setProgress(i, false)
            playbackSeekBar.progress = i
        }


            }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isExiting || !isPlaying) {
                isFrameCallbackPosted = false
                lastFrameNanos = 0L
                return
            }

            advancePlayhead(frameTimeNanos)
            if (!isPlaying) return

            renderInterpolated(frameTimeNanos)

            choreographer.postFrameCallback(this)
            isFrameCallbackPosted = true
        }

    }



    private fun updateCameraSmooth(pos: LatLng) {
        val map = googleMap ?: return

        val alpha = 0.12   // 0.08–0.18 (sweet spot pre 2×)

        camLat = if (camLat.isNaN()) pos.latitude
        else camLat + (pos.latitude - camLat) * alpha

        camLon = if (camLon.isNaN()) pos.longitude
        else camLon + (pos.longitude - camLon) * alpha

        val smoothPos = LatLng(camLat, camLon)

        map.moveCamera(CameraUpdateFactory.newLatLng(smoothPos))
    }





    // -----------------------------
    // Google Play Services
    // -----------------------------
    private fun checkGooglePlayServices(): Boolean {
        val api = GoogleApiAvailability.getInstance()
        val result = api.isGooglePlayServicesAvailable(this)

        return if (result == ConnectionResult.SUCCESS) {
            true
        } else {
            val message = api.getErrorString(result)
            Toast.makeText(this, "Chyba Google Play Services: $message", Toast.LENGTH_LONG).show()
            false
        }
    }
}









