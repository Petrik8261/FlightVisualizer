package sk.dubrava.flightvisualizer.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
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
import sk.dubrava.flightvisualizer.core.GeoMath
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import sk.dubrava.flightvisualizer.ui.start.StartActivity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MAP_DEBUG"




        // Default dt, ak log nemá dtSec (5 Hz)
        private const val DEFAULT_DT_SEC = 0.2

        // Kamera je drahá -> update menej často, marker+HUD môžu ísť 60 fps

        private const val MIN_SEG_DUR_SEC = 0.02
        private const val RENDER_HZ = 30.0

        // Camera feel tuning
        private const val CAM_TILT_DEG = 60f            // pseudo-3D
        private const val CAM_ZOOM_ALPHA = 0.12         // tlmenie zoomu
        private const val CAM_BEARING_ALPHA = 0.18      // tlmenie bearingu
    }

    private var googleMap: GoogleMap? = null
    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

    private var lastCrsDeg: Float? = null
    private var lastPosForCrs: LatLng? = null

    private var lastAltForVsM: Double? = null
    private var lastVsMps: Double? = null

    private lateinit var vehicleType: String
    private lateinit var flightHelper: FlightHelper

    private var iconPlane: com.google.android.gms.maps.model.BitmapDescriptor? = null
    private var iconDrone: com.google.android.gms.maps.model.BitmapDescriptor? = null

    private lateinit var playbackSeekBar: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnStepBack: Button
    private lateinit var btnStepForward: Button

    private lateinit var attitudeView: AttitudeHudView

    private var playbackSpeed = 2.0

    private var smoothZoom: Double? = null
    private var smoothBearing: Double? = null

    // Playback state (time-based)
    private var isPlaying = false
    private var isExiting = false

    private var playbackTimeSec = 0.0        // playhead v sekundách od začiatku
    private var segmentIndex = 0             // segment i..i+1
    private var segmentT0Sec = 0.0           // kumulatívny čas začiatku segmentu
    private var segmentDurSec = DEFAULT_DT_SEC

    private val renderIntervalNanos = (1_000_000_000L / RENDER_HZ).toLong()
    private var lastRenderNanos = 0L

    private var followCamera = true

    private var lastFrameNanos = 0L          // pre dt výpočet
    private var lastCameraNanos = 0L         // throttle mapy

    private val choreographer by lazy { Choreographer.getInstance() }
    private var isFrameCallbackPosted = false

    private var flightMarker: Marker? = null

    private val framesCount: Int
        get() = min(routeLatLng.size, flightPoints.size)

    private val lastFrameIndex: Int
        get() = (framesCount - 1).coerceAtLeast(0)

    private fun zoomFromAltMeters(altM: Double): Double {
        val a = altM.coerceIn(0.0, 4000.0)

        return when {
            a < 100.0  -> 18.0
            a < 500.0  -> lerp(18.0, 15.5, (a - 100.0) / 400.0)
            a < 2000.0 -> lerp(15.5, 13.0, (a - 500.0) / 1500.0)
            else       -> lerp(13.0, 11.5, (a - 2000.0) / 2000.0)
        }
    }

    private fun hasFrames(): Boolean = framesCount > 0

    // -----------------------------
    // Helpers
    // -----------------------------
    private fun ema(prev: Double, x: Double, alpha: Double): Double =
        prev + alpha * (x - prev)

    private fun emaAngle(prevDeg: Double, xDeg: Double, alpha: Double): Double {
        var d = (xDeg - prevDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return norm360(prevDeg + alpha * d)
    }


    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun lerpAngleDeg(aDeg: Double, bDeg: Double, t: Double): Double {
        var diff = bDeg - aDeg
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0
        return norm360(aDeg + diff * t)
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

    private fun segDurationSec(i: Int): Double {
        val fp = flightPoints.getOrNull(i) ?: return DEFAULT_DT_SEC
        val dt = fp.dtSec
        return if (dt.isFinite() && dt > 0.0) dt else DEFAULT_DT_SEC
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
            segmentDurSec = DEFAULT_DT_SEC
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
        segmentDurSec = segDurationSec(i).coerceAtLeast(MIN_SEG_DUR_SEC)
    }

    private fun seekToIndex(i: Int) {
        if (!hasFrames()) return
        val idx = i.coerceIn(0, lastFrameIndex)

        // prepočítaj čas na začiatok segmentu idx
        val targetSeg = idx.coerceIn(0, (framesCount - 2).coerceAtLeast(0))
        var t0 = 0.0
        for (k in 0 until targetSeg) t0 += segDurationSec(k)
        seekToTimeSec(t0)

        // hneď zobraz "keyframe" (bez animácie)
        val p = routeLatLng[idx]
        flightMarker?.position = p
        googleMap?.moveCamera(CameraUpdateFactory.newLatLng(p))

        // aj HUD nech sedí presne na bode (bez lerpu)
        val fp = flightPoints[idx]
        attitudeView.pitchDeg = (fp.pitchDeg ?: 0.0).toFloat()
        attitudeView.rollDeg  = (fp.rollDeg  ?: 0.0).toFloat()

        val yaw = fp.yawDeg?.takeIf { it.isFinite() }
            ?: fp.headingDeg?.takeIf { it.isFinite() }
            ?: if (idx > 0)
                headingDegrees(routeLatLng[idx - 1], routeLatLng[idx])
            else 0.0

        attitudeView.headingDeg = norm360(yaw).toFloat()

        attitudeView.altitudeFt = (fp.altitudeM * 3.28084).toFloat()

        lastAltForVsM = fp.altitudeM
        lastVsMps = fp.vsMps

        val kts = fp.speedMps?.takeIf { it.isFinite() }?.let { it * 1.94384 }
        attitudeView.speedKts = kts?.toFloat()

        val vs = fp.vsMps?.takeIf { it.isFinite() }?.let { (it * 196.850394).toFloat() }
        attitudeView.vsFpm = vs

        flightMarker?.rotation = norm360(yaw).toFloat()

        if (playbackSeekBar.progress != idx) playbackSeekBar.progress = idx
    }

    // -----------------------------
    // Lifecycle
    // -----------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""

        flightHelper = FlightHelper(contentResolver)
        vehicleType = intent.getStringExtra(AppNav.EXTRA_VEHICLE_TYPE) ?: AppNav.VEHICLE_PLANE

        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnStepBack = findViewById(R.id.btnStepBack)
        btnStepForward = findViewById(R.id.btnStepForward)

        attitudeView = findViewById(R.id.attitudeView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitToPrevious()
            }
        })

        btnPlay.setOnClickListener { if (isPlaying) pausePlayback() else startPlayback() }
        btnStop.setOnClickListener { resetPlayback() }

        btnStepBack.setOnClickListener {
            pausePlayback()
            seekToIndex(playbackSeekBar.progress - 1)
        }

        btnStepForward.setOnClickListener {
            pausePlayback()
            seekToIndex(playbackSeekBar.progress + 1)
        }

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
        pausePlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            R.id.action_back -> { exitToPrevious(); true }
            R.id.action_home -> { goHome(); true }
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

    private fun exitToPrevious() {
        beginExitMode()
        window.decorView.post {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun goHome() {
        beginExitMode()

        val i = Intent(
            this,
            StartActivity::class.java
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }

        window.decorView.post {
            startActivity(i)
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

    // -----------------------------
    // Map
    // -----------------------------
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE

        iconPlane = scaledMarker(R.drawable.aircraft_top, 56, 56)
        iconDrone = scaledMarker(R.drawable.dron, 56, 56)


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

        playbackSpeed = when (flightPoints.firstOrNull()?.source) {
            LogType.MSFS -> 4.0
            LogType.DRONE -> 1.6
            else -> 2.0
        }

        routeLatLng = flightHelper.buildRoute(flightPoints)
        if (!hasFrames()) {
            Toast.makeText(this, "Nedá sa prehrávať – nesedia dáta.", Toast.LENGTH_LONG).show()
            return
        }

        playbackSeekBar.max = lastFrameIndex
        playbackSeekBar.progress = 0

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || !hasFrames()) return
                pausePlayback()
                seekToIndex(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = pausePlayback()
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        drawRoute(routeLatLng)

        val startPoint = routeLatLng.first()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))

        val icon = if (vehicleType == AppNav.VEHICLE_DRONE) iconDrone else iconPlane

        flightMarker = googleMap?.addMarker(
            MarkerOptions()
                .position(startPoint)
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .flat(true)
        )

        // nastav playhead na začiatok
        seekToTimeSec(0.0)
        seekToIndex(0)
    }

    // -----------------------------
    // Playback core
    // -----------------------------
    private fun startPlayback() {
        if (isExiting || !hasFrames() || isPlaying) return
        isPlaying = true
        btnPlay.text = "Pause"

        // reset timing, aby prvý frame nemal obrovský dt
        lastFrameNanos = 0L
        lastCameraNanos = 0L
        lastRenderNanos = 0L

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

        seekToTimeSec(0.0)
        playbackSeekBar.progress = 0

        val start = routeLatLng.first()
        flightMarker?.position = start
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 15f))

        seekToIndex(0)
    }

    private fun advancePlayhead(frameTimeNanos: Long) {
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            return
        }

        val dtSec = (frameTimeNanos - lastFrameNanos).coerceAtMost(50_000_000L) / 1_000_000_000.0
        lastFrameNanos = frameTimeNanos

        playbackTimeSec += dtSec * playbackSpeed


        // posuň segmenty, ak sme prešli cez koniec segmentu
        val total = totalDurationSec()
        if (playbackTimeSec >= total) {
            playbackTimeSec = total
            isPlaying = false
            btnPlay.text = "Play"
            return
        }

        while (playbackTimeSec >= segmentT0Sec + segmentDurSec && segmentIndex < framesCount - 2) {
            segmentT0Sec += segmentDurSec
            segmentIndex++
            segmentDurSec = segDurationSec(segmentIndex).coerceAtLeast(MIN_SEG_DUR_SEC)
        }
    }

    private fun renderInterpolated(frameTimeNanos: Long) {
        if (isExiting) return
        if (framesCount < 2) return

        val i = segmentIndex.coerceIn(0, framesCount - 2)
        val a = flightPoints[i]
        val b = flightPoints[i + 1]

        val ta = segmentT0Sec
        val tb = segmentT0Sec + segmentDurSec
        val tt = if (tb > ta) ((playbackTimeSec - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0

        // position
        val lat = lerp(a.latitude, b.latitude, tt)
        val lon = lerp(a.longitude, b.longitude, tt)
        val pos = LatLng(lat, lon)
        flightMarker?.position = pos

        // --- CRS (track) ---
        val prevPos = lastPosForCrs
        val crs = if (prevPos != null) {
            val d = GeoMath.distanceMeters(prevPos, pos)
            if (d >= 1.0) headingDegrees(prevPos, pos).toFloat() else lastCrsDeg
        } else lastCrsDeg

        attitudeView.crsDeg = crs
        if (crs != null) lastCrsDeg = crs
        lastPosForCrs = pos

        // attitude
        val pitchA = a.pitchDeg ?: 0.0
        val pitchB = b.pitchDeg ?: pitchA
        val rollA  = a.rollDeg  ?: 0.0
        val rollB  = b.rollDeg  ?: rollA


        val pitch = lerp(pitchA, pitchB, tt)
        val roll  = lerp(rollA,  rollB,  tt)

        val baseTilt = 55f
        val pitchEffect = (pitch * 0.5).coerceIn(-8.0, 8.0)
        val dynamicTilt = (baseTilt + pitchEffect.toFloat()).coerceIn(40f, 70f)


        val yawA = (a.yawDeg?.takeIf { it.isFinite() }
            ?: a.headingDeg?.takeIf { it.isFinite() })

            ?: headingDegrees(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude))

        val yawB = (b.yawDeg?.takeIf { it.isFinite() }
            ?: b.headingDeg?.takeIf { it.isFinite() })
            ?: headingDegrees(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude))


        val yaw = lerpAngleDeg(yawA, yawB, tt)

        attitudeView.pitchDeg = pitch.toFloat()
        attitudeView.rollDeg = roll.toFloat()
        attitudeView.headingDeg = yaw.toFloat()

        // speed / alt / vs
        val speedMps = if (a.speedMps != null && b.speedMps != null)
            lerp(a.speedMps, b.speedMps, tt)
        else a.speedMps

        val speedKts = speedMps?.takeIf { it.isFinite() }?.let { it * 1.94384 }
        attitudeView.speedKts = speedKts?.toFloat()


        val altM = lerp(a.altitudeM, b.altitudeM, tt)
        attitudeView.altitudeFt = (altM * 3.28084).toFloat()

// --- VS (preferuj z logu, inak z altitude diff) ---
        // --- VS (preferuj z logu, inak dopočítaj zo segmentu a->b) ---
        val vsFromLog = when {
            a.vsMps != null && b.vsMps != null -> lerp(a.vsMps, b.vsMps, tt)
            a.vsMps != null -> a.vsMps
            else -> null
        }

        val dtSeg = segmentDurSec.coerceAtLeast(0.02)
        val vsFromSeg = (b.altitudeM - a.altitudeM) / dtSeg   // + = stúpa, - = klesá

        val vsFinal = (vsFromLog ?: vsFromSeg).takeIf { it.isFinite() }
        attitudeView.vsFpm = vsFinal?.let { (it * 196.850394).toFloat() }  // m/s -> fpm


// uložiť anchor pre ďalší frame
        lastAltForVsM = altM
        if (vsFinal != null) lastVsMps = vsFinal

        attitudeView.vsFpm = vsFinal?.let { (it * 196.850394).toFloat() }  // m/s -> fpm



        if (followCamera) {
            val map = googleMap ?: return

            // 1) target zoom z výšky
            val targetZoom = zoomFromAltMeters(altM)


            smoothZoom = if (smoothZoom == null) targetZoom else ema(smoothZoom!!, targetZoom, CAM_ZOOM_ALPHA)

            // 2) bearing podľa yaw
            val rollEffect = (roll * 0.25).coerceIn(-6.0, 6.0)
            val targetBearing = norm360(yaw + rollEffect)


            smoothBearing = if (smoothBearing == null) targetBearing else emaAngle(smoothBearing!!, targetBearing, CAM_BEARING_ALPHA)

            val current = map.cameraPosition

            val newPos = com.google.android.gms.maps.model.CameraPosition.Builder(current)
                .target(pos)
                .zoom(smoothZoom!!.toFloat())
                .tilt(dynamicTilt)
                .bearing(smoothBearing!!.toFloat())
                .build()

            map.moveCamera(CameraUpdateFactory.newCameraPosition(newPos))
        }





        // SeekBar len keď sa zmení segment (nie každý frame)
        if (playbackSeekBar.progress != i) {
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

            if (!isPlaying) {
                isFrameCallbackPosted = false
                lastFrameNanos = 0L
                return
            }

            // ✅ render throttle (30 fps)
            if (lastRenderNanos == 0L || frameTimeNanos - lastRenderNanos >= renderIntervalNanos) {
                lastRenderNanos = frameTimeNanos
                renderInterpolated(frameTimeNanos)
            }

            choreographer.postFrameCallback(this)
            isFrameCallbackPosted = true
        }
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
    private fun computeAutoPlaybackSpeed(targetDurationSec: Double): Double {
        val total = totalDurationSec()
        if (total <= 0.0) return 1.0

        val speed = total / targetDurationSec

        // ochrana proti extrémom
        return speed.coerceIn(0.5, 20.0)
    }

}










