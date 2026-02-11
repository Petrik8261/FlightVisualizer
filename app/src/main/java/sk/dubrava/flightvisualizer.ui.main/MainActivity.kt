package sk.dubrava.flightvisualizer.ui.main

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View as AndroidView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.core.FlightHelper
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.ui.main.AttitudeHudView
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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


    private var isPlaying = false
    private var currentIndex = 0
    private val playHandler = Handler(Looper.getMainLooper())
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
        playHandler.removeCallbacksAndMessages(null)
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
        playHandler.removeCallbacksAndMessages(null)

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
                currentIndex = progress.coerceIn(0, lastFrameIndex)
                renderFrameSafe(currentIndex)
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
        attitudeView.speed = spd?.toFloat()
        attitudeView.altitude = altM.toFloat()

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
        if (isExiting) return
        if (!hasFrames()) return
        if (isPlaying) return

        isPlaying = true
        btnPlay.text = "Pause"
        currentIndex = playbackSeekBar.progress.coerceIn(0, lastFrameIndex)
        playHandler.post(playStepRunnable)
    }

    private fun pausePlayback() {
        isPlaying = false
        playHandler.removeCallbacks(playStepRunnable)
        playHandler.removeCallbacksAndMessages(null)
        btnPlay.text = "Play"
    }

    private fun resetPlayback() {
        pausePlayback()
        if (!hasFrames()) return

        currentIndex = 0
        playbackSeekBar.progress = 0

        val start = routeLatLng.first()
        flightMarker?.position = start
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 15f))

        renderFrameSafe(0)
    }

    private val playStepRunnable = object : Runnable {
        override fun run() {
            if (isExiting) return
            if (!isPlaying || !hasFrames()) return

            if (currentIndex > lastFrameIndex) {
                isPlaying = false
                btnPlay.text = "Play"
                return
            }

            renderFrameSafe(currentIndex)
            currentIndex++

            val delay = (200L / playbackSpeed).toLong().coerceAtLeast(40L)
            playHandler.postDelayed(this, delay)
        }
    }

    private fun cyclePlaybackSpeed() {
        playbackSpeed = when (playbackSpeed) {
            2.0 -> 1.0
            1.0 -> 2.0
            else -> 2.0
        }

        val label = when (playbackSpeed) {
            2.0 -> "1X"
            1.0 -> "0.5X"
            else -> "1X"
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









