package sk.dubrava.flightvisualizer.ui.main

import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View as AndroidView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.filament.View
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.core.FlightHelper
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MAP_DEBUG"

        private const val DEBUG_AXIS_TEST = false

        // --- MODEL AXIS TUNING (SceneView: X=right, Y=up, Z=forward) ---
        private const val YAW_OFFSET_DEG = 180f
        private const val YAW_SIGN = 1f

        private const val PITCH_SIGN = 1f
        private const val ROLL_SIGN = 1f

        private val BASE_ROTATION = Rotation(
            x = 0f,
            y = 180f,
            z = 0f
        )

        // --- CAMERA PRESETS ---
        private const val CAM_TOP_Y = 6.0f
        private const val CAM_TOP_Z = 0.0f
        private const val CAM_TOP_PITCH = -90f

        private const val CAM_TILT_Y = 7.5f
        private const val CAM_TILT_Z = 2.5f
        private const val CAM_TILT_PITCH = -75f

        // --- SCALE ---
        private const val BASE_MODEL_SCALE = 0.04f
        private const val SCALE_EFFECT = 0.18f
        private const val SCALE_SMOOTH_ALPHA = 0.18f
        private const val ALT_DELTA_MAX_M = 3.0f

        private const val DRONE_SCALE = 0.12f
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

    private lateinit var tvAltitude: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvPitchRoll: TextView
    private lateinit var tvVerticalSpeed: TextView
    private lateinit var tvHeading: TextView

    private var isPlaying = false
    private var currentIndex = 0
    private val playHandler = Handler(Looper.getMainLooper())
    private var playbackSpeed = 2.0

    private var flightMarker: Marker? = null

    // 3D
    private lateinit var planeView: SceneView
    private var planeNode: ModelNode? = null

    private var modelReady = false
    private var pendingRotation: Rotation? = null
    private var pendingScale: Scale? = null

    // smoothing
    private var lastRoll = 0.0
    private var lastPitch = 0.0
    private var lastHeading = 0.0
    private var lastYaw = 0.0

    private var lastScale = BASE_MODEL_SCALE.toDouble()
    private var lastAltitudeM = Double.NaN

    private enum class CameraMode { TOP, TILT }
    private var cameraMode: CameraMode = CameraMode.TOP

    // “anti-crash” prepínač – keď odchádzame, už nič 3D neupdatuje
    private var isExiting = false

    // -----------------------------
    // Helpers
    // -----------------------------
    private fun clamp(v: Double, min: Double, max: Double): Double = max(min, min(v, max))

    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)

        val aa = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        val c = 2 * atan2(sqrt(aa), sqrt(1 - aa))
        return R * c
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

    private fun smoothAngle(prev: Double, new: Double, alpha: Double = 0.15): Double {
        var diff = new - prev
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0
        return prev + diff * alpha
    }

    private fun applyCameraMode(mode: CameraMode) {
        cameraMode = mode
        val cam = planeView.cameraNode
        when (mode) {
            CameraMode.TOP -> {
                cam.position = Position(0f, CAM_TOP_Y, CAM_TOP_Z)
                cam.rotation = Rotation(CAM_TOP_PITCH, 0f, 0f)
            }
            CameraMode.TILT -> {
                cam.position = Position(0f, CAM_TILT_Y, CAM_TILT_Z)
                cam.rotation = Rotation(CAM_TILT_PITCH, 0f, 0f)
            }
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

        tvAltitude = findViewById(R.id.tvAltitude)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvPitchRoll = findViewById(R.id.tvPitchRoll)
        tvVerticalSpeed = findViewById(R.id.tvVerticalSpeed)
        tvHeading = findViewById(R.id.tvHeading)

        planeView = findViewById(R.id.planeView)
        setup3DScene()

        // systémový BACK – nech ide cez safeExit (nie default teardown cestou)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                safeExitToPrevious()
            }
        })

        btnPlay.setOnClickListener { if (isPlaying) pausePlayback() else startPlayback() }
        btnStop.setOnClickListener { resetPlayback() }

        btnStepBack.setOnClickListener {
            pausePlayback()
            if (routeLatLng.isEmpty()) return@setOnClickListener
            currentIndex = (currentIndex - 1).coerceAtLeast(0)
            showFrame(currentIndex)
        }

        btnStepForward.setOnClickListener {
            pausePlayback()
            if (routeLatLng.isEmpty()) return@setOnClickListener
            currentIndex = (currentIndex + 1).coerceAtMost(routeLatLng.lastIndex)
            showFrame(currentIndex)
        }

        btnSpeed.setOnClickListener { cyclePlaybackSpeed() }
        btnSpeed.setOnLongClickListener {
            val next = if (cameraMode == CameraMode.TOP) CameraMode.TILT else CameraMode.TOP
            applyCameraMode(next)
            Toast.makeText(this, "Kamera: ${if (next == CameraMode.TOP) "TOP" else "TILT"}", Toast.LENGTH_SHORT).show()
            true
        }

        findViewById<FloatingActionButton>(R.id.btnMapType).setOnClickListener {
            showMapTypeBottomSheet()
        }

        if (!checkGooglePlayServices()) return

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as? SupportMapFragment

        if (mapFragment == null) {
            Toast.makeText(this, "MapFragment sa nenašiel!", Toast.LENGTH_LONG).show()
            return
        }

        mapFragment.getMapAsync(this)
    }

    override fun onStop() {
        pausePlayback()
        super.onStop()
    }

    override fun onDestroy() {
        // NEVOLAJME destroy/removeChild/release – to u teba spúšťa Filament crash.
        // Len zrušíme callbacky aby nič nebežalo.
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

        // “odpoj” 3D tak, aby už nereagovalo, ale NEROB destroy()
        try {
            if (::planeView.isInitialized) {
                planeView.visibility = AndroidView.INVISIBLE
                // nech nekonzumuje nič (nie je nutné, ale ok)
                planeView.setOnTouchListener(null)
            }
        } catch (_: Exception) {}

        // už len zruš referenciu (GC neskôr)
        planeNode = null
        modelReady = false
        pendingRotation = null
        pendingScale = null
    }

    private fun safeExitToPrevious() {
        beginExitMode()
        finish()
        // bez animácie = menšia šanca že EGL/Surface teardown “trafí” bug
        overridePendingTransition(0, 0)
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
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
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

        playbackSeekBar.max = routeLatLng.size - 1
        playbackSeekBar.progress = 0
        currentIndex = 0

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (routeLatLng.isEmpty()) return
                currentIndex = progress.coerceIn(0, routeLatLng.lastIndex)
                showFrame(currentIndex)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = pausePlayback()
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        drawRoute(routeLatLng)

        val startPoint = routeLatLng.first()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))

        showFrame(0)
    }

    // -----------------------------
    // HUD + 3D update
    // -----------------------------
    private fun updateUiForIndex(index: Int) {
        if (isExiting) return
        if (index !in flightPoints.indices) return

        val fp = flightPoints[index]
        val altM = fp.altitude

        if (index == 0 || lastAltitudeM.isNaN()) {
            lastAltitudeM = altM
            lastScale = BASE_MODEL_SCALE.toDouble()
        }

        tvAltitude.text = "ALT: ${altM.toInt()} m"

        val rawHeading = fp.heading
        val rawPitch = fp.pitch
        val rawRoll = fp.roll

        if (index == 0) {
            lastHeading = norm360(rawHeading)
            lastPitch = rawPitch
            lastRoll = rawRoll
            lastYaw = norm360(rawHeading)
        }

        val headingSmoothed = smoothAngle(lastHeading, rawHeading, 0.15)
        val pitchSmoothed = smoothAngle(lastPitch, rawPitch, 0.15)
        val rollSmoothed = smoothAngle(lastRoll, rawRoll, 0.15)

        val headingNorm = norm360(headingSmoothed)
        lastHeading = headingNorm
        lastPitch = pitchSmoothed
        lastRoll = rollSmoothed

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

        tvHeading.text = String.format(Locale.ROOT, "HDG: %03.0f°", yawNorm)

        val pitchDegDisplay = -pitchSmoothed
        val rollDegDisplay = -rollSmoothed

        tvPitchRoll.text = String.format(
            Locale.ROOT,
            "ROLL: %.1f° | PITCH: %.1f° | YAW: %.1f°",
            rollDegDisplay, pitchDegDisplay, yawNorm
        )

        tvSpeed.text = if (fp.speedKmh != null) {
            String.format(Locale.ROOT, "SPD: %.0f km/h", fp.speedKmh)
        } else "SPD: N/A"

        tvVerticalSpeed.text = if (fp.vsMps != null) {
            String.format(Locale.ROOT, "VS: %.1f m/s", fp.vsMps)
        } else "VS: N/A"

        // 3D update (len ak model ready a neodchádzame)
        if (!modelReady) return

        val yawDeg3d = (YAW_SIGN * (yawNorm + YAW_OFFSET_DEG)).toFloat()
        val pitchDeg3d = (PITCH_SIGN * (-pitchSmoothed)).toFloat()
        val rollDeg3d = (ROLL_SIGN * rollSmoothed).toFloat()

        val finalRotation =
            BASE_ROTATION + Rotation(
                x = pitchDeg3d,
                y = -yawDeg3d,
                z = rollDeg3d
            )

        val finalScale: Scale = if (vehicleType == AppNav.VEHICLE_DRONE) {
            Scale(DRONE_SCALE)
        } else {
            val altDelta = altM - lastAltitudeM
            lastAltitudeM = altM

            val climbFactor = (altDelta / ALT_DELTA_MAX_M).coerceIn(-1.0, 1.0).toFloat()
            val targetScale = BASE_MODEL_SCALE * (1f + climbFactor * SCALE_EFFECT)
            val smoothedScale = (lastScale + (targetScale - lastScale) * SCALE_SMOOTH_ALPHA).toFloat()
            lastScale = smoothedScale.toDouble()
            Scale(smoothedScale)
        }

        planeNode?.rotation = finalRotation
        planeNode?.scale = finalScale
    }

    // -----------------------------
    // Frame update
    // -----------------------------
    private fun showFrame(index: Int) {
        if (isExiting) return
        if (routeLatLng.isEmpty()) return
        if (index !in routeLatLng.indices) return

        val point = routeLatLng[index]
        googleMap?.moveCamera(CameraUpdateFactory.newLatLng(point))
        flightMarker?.position = point

        updateUiForIndex(index)
        playbackSeekBar.progress = index
    }

    // -----------------------------
    // Playback
    // -----------------------------
    private fun startPlayback() {
        if (isExiting) return
        if (routeLatLng.isEmpty()) return
        if (isPlaying) return

        isPlaying = true
        btnPlay.text = "Pause"
        currentIndex = playbackSeekBar.progress
        playHandler.post(playStepRunnable)
    }

    private fun pausePlayback() {
        isPlaying = false
        playHandler.removeCallbacks(playStepRunnable)
        btnPlay.text = "Play"
    }

    private fun resetPlayback() {
        pausePlayback()
        if (routeLatLng.isEmpty()) return

        currentIndex = 0
        playbackSeekBar.progress = 0
        val start = routeLatLng.first()
        flightMarker?.position = start
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 15f))
        showFrame(0)
    }

    private val playStepRunnable = object : Runnable {
        override fun run() {
            if (isExiting) return
            if (!isPlaying || routeLatLng.isEmpty()) return

            if (currentIndex >= routeLatLng.size) {
                isPlaying = false
                btnPlay.text = "Play"
                return
            }

            showFrame(currentIndex)
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
    // Map type bottom sheet
    // -----------------------------
    private fun showMapTypeBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.map_type_bottom_sheet, null)

        view.findViewById<Button>(R.id.btnMapNormal).setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_NORMAL
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnMapSatellite).setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnMapHybrid).setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_HYBRID
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnMapTerrain).setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_TERRAIN
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
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

    // -----------------------------
    // 3D setup
    // -----------------------------
    private fun setup3DScene() {
        planeView.apply {
            setZOrderOnTop(true)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.TRANSLUCENT)

            scene.skybox = null

            renderer.clearOptions = renderer.clearOptions.apply {
                clear = true
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
            }

            view.blendMode = View.BlendMode.TRANSLUCENT

            applyCameraMode(CameraMode.TOP)

            // blokuj dotyk aby si “neťahal” scenou
            setOnTouchListener { _, _ -> true }
        }

        modelReady = false
        pendingRotation = null
        pendingScale = null

        val isDrone = (vehicleType == AppNav.VEHICLE_DRONE)
        val modelPath = if (isDrone) "models/drone.glb" else "models/airplane_lowpoly_final.glb"
        val startScale = if (isDrone) DRONE_SCALE else BASE_MODEL_SCALE

        planeNode = ModelNode(
            position = Position(0f, 0f, 0f),
            rotation = Rotation(0f, 0f, 0f),
            scale = Scale(startScale)
        ).apply { isVisible = false }

        lastScale = startScale.toDouble()

        planeView.addChild(planeNode!!)

        planeNode!!.loadModelAsync(
            context = this,
            lifecycle = lifecycle,
            glbFileLocation = modelPath,
            onLoaded = {
                modelReady = true

                // aplikuj pending (ak prišlo skôr než model)
                pendingScale?.let { planeNode?.scale = it }
                pendingRotation?.let { planeNode?.rotation = it }

                if (pendingRotation == null) {
                    planeNode?.rotation = Rotation(0f, 0f, 0f)
                }

                if (DEBUG_AXIS_TEST) {
                    planeNode?.rotation = Rotation(0f, 0f, 90f)
                }

                planeNode?.isVisible = true
                Log.i(TAG, "✔️ 3D model načítaný: $modelPath")
            },
            onError = { e ->
                Log.e(TAG, "Chyba pri načítaní 3D modelu: $modelPath", e)
                Toast.makeText(this, "Chyba modelu: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}








