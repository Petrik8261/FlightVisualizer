package sk.dubrava.flightvisualizer.ui.main

import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import org.w3c.dom.Element
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.TimeSource
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*
import sk.dubrava.flightvisualizer.core.FlightHelper


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MAP_DEBUG"

        private const val TAIL_FORWARD_FIX_ENABLED = true
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

        // scale efekt podľa zmeny ALT (pre lietadlo)
        private const val BASE_MODEL_SCALE = 0.07f
        private const val SCALE_EFFECT = 0.18f
        private const val SCALE_SMOOTH_ALPHA = 0.18f
        private const val ALT_DELTA_MAX_M = 3.0f

        // --- SANITY / QUALITY THRESHOLDS ---
        private const val MIN_DT_SEC = 0.05
        private const val MAX_STEP_DIST_M = 2000.0
        private const val MAX_SPEED_KMH = 250.0
        private const val MAX_VS_MPS = 30.0

        private const val ALLOW_ESTIMATED_SPEED_FROM_KML_TOUR = false



        // --- CAMERA PRESETS (3/4 view) ---
        private val CAM_PLANE_BASE_POS = Position(0f, 4.3f, 6.8f)  // bolo 5.5 / 9.0
        private val CAM_PLANE_BASE_ROT = Rotation(-32f, 0f, 0f)

        private val CAM_DRONE_BASE_POS = Position(0f, 4.0f, 8.0f)  // bolo 5.0 / 12.0
        private val CAM_DRONE_BASE_ROT = Rotation(-28f, 0f, 0f)


        // Drone "zoom" efekt cez kameru (nie nafukovanie modelu)
        private const val DRONE_CAM_ZOOM_EFFECT = 0.12f      // sila efektu
        private const val DRONE_CAM_SMOOTH_ALPHA = 0.18f     // smoothing (0..1)
        private const val DRONE_CAM_DELTA_MAX_M = 3.0f       // alt delta, kde efekt saturuje
        private const val DRONE_CAM_Z_MIN = 5.5f
        private const val DRONE_CAM_Z_MAX = 12.0f


        // Drone scale (konštantné)
        private const val DRONE_SCALE = 0.16f

    }

    private var lastCamZ: Double = Double.NaN
    private var lastCamAltitudeM: Double = Double.NaN


    private var googleMap: GoogleMap? = null
    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

    private lateinit var vehicleType: String
    private lateinit var flightHelper: FlightHelper


    // --- 3D gating (aby nebol skok) ---
    private var modelReady = false
    private var pendingRotation: Rotation? = null
    private var pendingScale: Scale? = null

    private lateinit var playbackSeekBar: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnStepBack: Button
    private lateinit var btnStepForward: Button

    // HUD
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

    // --- 3D ---
    private lateinit var planeView: SceneView
    private var planeNode: ModelNode? = null

    // smoothing stav
    private var lastRoll = 0.0
    private var lastPitch = 0.0
    private var lastHeading = 0.0
    private var lastYaw = 0.0

    private var lastScale = BASE_MODEL_SCALE.toDouble()
    private var lastAltitudeM = Double.NaN

    // -----------------------------
    // Basic helpers
    // -----------------------------
    private fun clamp(v: Double, min: Double, max: Double): Double = max(min, min(v, max))

    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
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

    // -----------------------------
    // Android lifecycle
    // -----------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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

        val isTxtDefaultAttitude = (fp.pitch == 90.0 && fp.roll == 0.0)

        var rollForHud = rollSmoothed
        var pitchForHud = pitchSmoothed

        if (isTxtDefaultAttitude) {
            val vs = fp.vsMps
            val spdHud = fp.speedKmh
            if (vs != null && spdHud != null && spdHud > 1.0) {
                val v = spdHud / 3.6
                val gammaDeg = Math.toDegrees(atan2(vs, v))
                pitchForHud = 90.0 + clamp(gammaDeg, -20.0, 20.0)
            }

            val window = 10
            if (index >= window + 1) {
                val p0 = flightPoints[index - window - 1]
                val p1 = flightPoints[index - window]
                val p2 = flightPoints[index]

                val coursePrev = headingDegrees(
                    LatLng(p0.latitude, p0.longitude),
                    LatLng(p1.latitude, p1.longitude)
                )
                val courseNow = headingDegrees(
                    LatLng(p1.latitude, p1.longitude),
                    LatLng(p2.latitude, p2.longitude)
                )

                val dCourseDeg = angleDiffDeg(courseNow, coursePrev)
                val dt = window.toDouble()

                val omega = Math.toRadians(dCourseDeg) / dt

                val distM = distanceMeters(
                    LatLng(p1.latitude, p1.longitude),
                    LatLng(p2.latitude, p2.longitude)
                )
                val v = distM / dt

                if (v > 1.0 && abs(dCourseDeg) > 0.05) {
                    val g = 9.80665
                    val bankRad = atan((v * omega) / g)
                    rollForHud = clamp(Math.toDegrees(bankRad), -60.0, 60.0)
                }
            }
        }

        tvHeading.text = String.format(Locale.ROOT, "HDG: %03.0f°", yawNorm)

        val pitchDegDisplayRaw = if (isTxtDefaultAttitude) pitchForHud - 90.0 else pitchSmoothed
        val rollDegDisplayRaw = if (isTxtDefaultAttitude) rollForHud else rollSmoothed

        val pitchDegDisplay = -pitchDegDisplayRaw
        val rollDegDisplay  = -rollDegDisplayRaw

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

        // ------------------
        // 3D ROTATION + SCALE (jediný blok)
        // ------------------
        val yawDeg3d   = (YAW_SIGN * (yawNorm + YAW_OFFSET_DEG)).toFloat()
        val pitchDeg3d = (PITCH_SIGN * pitchSmoothed).toFloat()
        val rollDeg3d  = (ROLL_SIGN * rollSmoothed).toFloat()

        val finalRotation =
            BASE_ROTATION +
                    Rotation(
                        x = pitchDeg3d,
                        y = -yawDeg3d,
                        z = -rollDeg3d
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

        // ------------------
// DRONE: "zoom" efekt cez kameru pri stúpaní/klesaní
// (model ostáva konštantný, hýbe sa kamera)
// ------------------
        if (vehicleType == AppNav.VEHICLE_DRONE) {
            val cam = planeView.cameraNode

            // init pri prvom frame
            if (lastCamAltitudeM.isNaN()) {
                lastCamAltitudeM = altM
                lastCamZ = cam.position.z.toDouble()
            }

            val altDelta = (altM - lastCamAltitudeM).toFloat()
            lastCamAltitudeM = altM

            // climbFactor: -1..1
            val climbFactor = (altDelta / DRONE_CAM_DELTA_MAX_M).coerceIn(-1f, 1f)

            // Default: stúpanie = mierne oddialiť kameru (model menší), klesanie = priblížiť
            val baseZ = (if (vehicleType == AppNav.VEHICLE_DRONE) CAM_DRONE_BASE_POS.z else CAM_PLANE_BASE_POS.z)
            val targetZ = (baseZ * (1f + climbFactor * DRONE_CAM_ZOOM_EFFECT))
                .coerceIn(DRONE_CAM_Z_MIN, DRONE_CAM_Z_MAX)

            val zPrev = lastCamZ.toFloat()
            val zNew = (zPrev + (targetZ - zPrev) * DRONE_CAM_SMOOTH_ALPHA)
                .coerceIn(DRONE_CAM_Z_MIN, DRONE_CAM_Z_MAX)

            lastCamZ = zNew.toDouble()

            // aplikuj kameru (len Z, aby sa nemenil uhol pohľadu)
            cam.position = Position(cam.position.x, cam.position.y, zNew)
        }
// Apply scale (gating)
        if (!modelReady) {
            pendingScale = finalScale
        } else {
            planeNode?.scale = finalScale
        }


        if (!modelReady) {
            pendingRotation = finalRotation
            pendingScale = finalScale
        } else {
            planeNode?.rotation = finalRotation
            planeNode?.scale = finalScale
        }
    }

    // -----------------------------
    // Frame update
    // -----------------------------
    private fun showFrame(index: Int) {
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

        val isDrone = (vehicleType == AppNav.VEHICLE_DRONE)

        planeView = findViewById(R.id.planeView)

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

            // 3/4 pohľad – lepšie vidno pitch/roll/yaw

            cameraNode.position = if (isDrone) CAM_DRONE_BASE_POS else CAM_PLANE_BASE_POS
            cameraNode.rotation = if (isDrone) CAM_DRONE_BASE_ROT else CAM_PLANE_BASE_ROT

// init pre drone camera-zoom (aby nebol skok)
            lastCamZ = cameraNode.position.z.toDouble()
            lastCamAltitudeM = Double.NaN


            setOnTouchListener { _, _ -> true }
        }

        modelReady = false
        pendingRotation = null
        pendingScale = null


        val modelPath = if (isDrone) "models/drone.glb" else "models/airplane_lowpoly_final.glb"

        val baseRotation = Rotation(0f, 0f, 0f)
        val basePos = Position(0f, 0f, 0f)
        val startScale = if (isDrone) DRONE_SCALE else BASE_MODEL_SCALE

        planeNode = ModelNode(
            position = basePos,
            rotation = baseRotation,
            scale = Scale(startScale)
        ).apply {
            isVisible = false
        }

        lastScale = startScale.toDouble()

        planeView.addChild(planeNode!!)

        planeNode!!.loadModelAsync(
            context = this,
            lifecycle = lifecycle,
            glbFileLocation = modelPath,
            onLoaded = {
                modelReady = true

                // aplikuj “prvý frame”, ak showFrame(0) už prebehol
                pendingScale?.let { planeNode?.scale = it }
                pendingRotation?.let { planeNode?.rotation = it }

                // fallback (ak ešte neboli dáta)
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





