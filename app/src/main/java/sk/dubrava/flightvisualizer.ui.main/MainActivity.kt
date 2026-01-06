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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
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
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.TimeSource
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MAP_DEBUG"

        const val EXTRA_VEHICLE_TYPE = "extra_vehicle_type"
        const val EXTRA_FILE_URI = "extra_file_uri"
        const val VEHICLE_DRONE = "DRONE"
        const val VEHICLE_PLANE = "PLANE"

        private const val TAIL_FORWARD_FIX_ENABLED = true

        // --- MODEL AXIS TUNING (SceneView: X=right, Y=up, Z=forward) ---
        private const val YAW_OFFSET_DEG = 0f      // ak bude nos o 90/180° mimo, sem dáš 90f alebo 180f
        private const val YAW_SIGN = 1f            // ak sa točí opačne, daj -1f

        private const val PITCH_SIGN = 1f          // ak pitch ide opačne, daj -1f
        private const val ROLL_SIGN = 1f           // ak roll ide opačne, daj -1f


        // scale efekt podľa zmeny ALT (pre lietadlo)
        private const val BASE_MODEL_SCALE = 0.03f
        private const val SCALE_EFFECT = 0.18f
        private const val SCALE_SMOOTH_ALPHA = 0.18f
        private const val ALT_DELTA_MAX_M = 3.0f

        // --- SANITY / QUALITY THRESHOLDS ---
        private const val MIN_DT_SEC = 0.05
        private const val MAX_STEP_DIST_M = 2000.0
        private const val MAX_SPEED_KMH = 250.0
        private const val MAX_VS_MPS = 30.0

        // či povolíme zobrazovať speed odhadovanú z KML Tour (gx:duration)
        private const val ALLOW_ESTIMATED_SPEED_FROM_KML_TOUR = false

        // Drone scale (konštantné, bez “pumpovania” podľa ALT)
        private const val DRONE_SCALE = 0.12f
    }

    private var googleMap: GoogleMap? = null
    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

    private lateinit var vehicleType: String

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
    private lateinit var tvGForce: TextView

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

    private fun normalizeAngleDeg(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
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

        vehicleType = intent.getStringExtra(EXTRA_VEHICLE_TYPE) ?: VEHICLE_PLANE

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
        tvGForce = findViewById(R.id.tvGForce)

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

        val uriStr = intent.getStringExtra(EXTRA_FILE_URI)
        if (uriStr.isNullOrBlank()) {
            Toast.makeText(this, "Chýba súbor (URI).", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uri = Uri.parse(uriStr)
        flightPoints = loadFlightFromUri(uri)

        if (flightPoints.size < 2) {
            Toast.makeText(this, "Nepodarilo sa načítať dostatok bodov zo súboru.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        routeLatLng = flightPoints.map { LatLng(it.latitude, it.longitude) }

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
        val endPoint = routeLatLng.last()



        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))
        showFrame(0)
    }

    // -----------------------------
    // LOAD FROM URI (dispatcher)
    // -----------------------------
    private fun loadFlightFromUri(uri: Uri): List<FlightPoint> {
        val name = guessFileName(uri)?.lowercase(Locale.ROOT) ?: ""
        return try {
            when {
                name.endsWith(".kml") -> loadFlightFromKmlUri(uri)
                name.endsWith(".txt") || name.endsWith(".csv") -> loadFlightFromTxtUri(uri)
                else -> {
                    val kmlTry = loadFlightFromKmlUri(uri)
                    if (kmlTry.isNotEmpty()) kmlTry else loadFlightFromTxtUri(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadFlightFromUri error", e)
            emptyList()
        }
    }

    private fun guessFileName(uri: Uri): String? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    // -----------------------------
    // KML FROM URI
    // -----------------------------
    private fun loadFlightFromKmlUri(uri: Uri): List<FlightPoint> {
        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()

        return inputStream.use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)
            doc.documentElement.normalize()

            val cameraNodes = doc.getElementsByTagName("Camera")
            val raw = mutableListOf<FlightPoint>()

            fun Element.getDouble(tag: String): Double {
                val list = getElementsByTagName(tag)
                if (list.length == 0) return 0.0
                return list.item(0).textContent.trim().toDoubleOrNull() ?: 0.0
            }

            fun findFlyToElement(node: org.w3c.dom.Node): Element? {
                var p: org.w3c.dom.Node? = node.parentNode
                while (p != null) {
                    if (p is Element && p.tagName.endsWith("FlyTo")) return p
                    p = p.parentNode
                }
                return null
            }

            fun readDurationSec(flyTo: Element?): Double {
                if (flyTo == null) return Double.NaN
                val a = flyTo.getElementsByTagName("gx:duration")
                if (a.length > 0) return a.item(0).textContent.trim().toDoubleOrNull() ?: Double.NaN
                val b = flyTo.getElementsByTagName("duration")
                if (b.length > 0) return b.item(0).textContent.trim().toDoubleOrNull() ?: Double.NaN
                return Double.NaN
            }

            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node is Element) {
                    val lon = node.getDouble("longitude")
                    val lat = node.getDouble("latitude")
                    val alt = node.getDouble("altitude")
                    val heading = node.getDouble("heading")
                    val tilt = node.getDouble("tilt")
                    val pitch = tilt - 90.0   // 90 -> 0 (rovno), 100 -> +10, 80 -> -10
                    val roll = node.getDouble("roll")

                    val flyTo = findFlyToElement(node)
                    val dt = readDurationSec(flyTo)

                    raw += FlightPoint(
                        time = i.toString(),
                        latitude = lat,
                        longitude = lon,
                        altitude = alt,
                        heading = heading,
                        pitch = pitch,
                        roll = roll,
                        dtSec = dt,
                        speedKmh = null,
                        vsMps = null,
                        yawDeg = null,
                        timeSource = TimeSource.KML_TOUR_DURATION
                    )
                }
            }

            if (raw.size < 2) return@use raw

            raw.mapIndexed { idx, fp ->
                if (idx == 0) {
                    fp.copy(
                        yawDeg = fp.heading,
                        vsMps = 0.0,
                        speedKmh = null
                    )
                } else {
                    val prev = raw[idx - 1]
                    val dt = fp.dtSec
                    val dtOk = dt.isFinite() && dt >= MIN_DT_SEC

                    val distM = distanceMeters(
                        LatLng(prev.latitude, prev.longitude),
                        LatLng(fp.latitude, fp.longitude)
                    )
                    val distOk = distM.isFinite() && distM <= MAX_STEP_DIST_M

                    val vs = if (dtOk && distOk) (fp.altitude - prev.altitude) / dt else null
                    val vsOk = vs != null && vs.isFinite() && abs(vs) <= MAX_VS_MPS

                    val canSpeed = dtOk && distOk && (
                            fp.timeSource == TimeSource.REAL_TIMESTAMP ||
                                    fp.timeSource == TimeSource.FIXED_RATE ||
                                    (ALLOW_ESTIMATED_SPEED_FROM_KML_TOUR && fp.timeSource == TimeSource.KML_TOUR_DURATION)
                            )

                    val spd = if (canSpeed) (distM / dt) * 3.6 else null
                    val spdOk = spd != null && spd.isFinite() && spd <= MAX_SPEED_KMH

                    fp.copy(
                        yawDeg = fp.heading,
                        vsMps = if (vsOk) vs else null,
                        speedKmh = if (spdOk) spd else null
                    )
                }
            }
        }
    }

    // -----------------------------
    // TXT / CSV FROM URI
    // -----------------------------
    private fun loadFlightFromTxtUri(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()

        val lines = input.bufferedReader().useLines { seq ->
            seq.map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
        if (lines.size < 2) return emptyList()

        fun splitLineSmart(line: String): List<String> =
            when {
                line.contains("\t") -> line.split(Regex("\t+"))
                line.contains(",") -> line.split(",")
                else -> line.split(Regex("\\s+"))
            }.map { it.trim() }

        fun toD(s: String?): Double? = s?.replace(",", ".")?.toDoubleOrNull()

        fun Double.isValidLat() = this.isFinite() && this in -90.0..90.0
        fun Double.isValidLon() = this.isFinite() && this in -180.0..180.0

        fun parseHmsToSec(s: String): Double? {
            val p = s.trim().split(":")
            if (p.size != 3) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            val sec = p[2].toIntOrNull() ?: return null
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        val header = splitLineSmart(lines.first()).map { it.lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k ->
                header.indexOfFirst { it == k }.takeIf { it >= 0 }
            }

        val iTime = idx("time") ?: return emptyList()
        val iLat = idx("latitude") ?: return emptyList()
        val iLon = idx("longitude") ?: return emptyList()
        val iAlt = idx("altitude", "altitude (m)") ?: return emptyList()
        val iX = idx("x")
        val iY = idx("y")
        val iZ = idx("z")

        data class RawRow(
            val tSec: Double,
            val lat: Double,
            val lon: Double,
            val alt: Double,
            val ax: Double?,
            val ay: Double?,
            val az: Double?
        )

        val rows = mutableListOf<RawRow>()
        var lastBaseSec = Double.NaN
        var sameSecondCounter = 0

        for (i in 1 until lines.size) {
            val t = splitLineSmart(lines[i])
            if (t.size < header.size) continue

            val baseSec = parseHmsToSec(t[iTime]) ?: continue
            val tSec = if (baseSec == lastBaseSec) {
                sameSecondCounter++
                baseSec + sameSecondCounter * 0.1
            } else {
                sameSecondCounter = 0
                baseSec
            }
            lastBaseSec = baseSec

            val lat = toD(t[iLat]) ?: continue
            val lon = toD(t[iLon]) ?: continue
            val alt = toD(t[iAlt]) ?: continue
            if (!lat.isValidLat() || !lon.isValidLon()) continue

            rows += RawRow(
                tSec = tSec,
                lat = lat,
                lon = lon,
                alt = alt,
                ax = iX?.let { toD(t[it]) },
                ay = iY?.let { toD(t[it]) },
                az = iZ?.let { toD(t[it]) }
            )
        }

        if (rows.size < 2) return emptyList()

        val out = mutableListOf<FlightPoint>()
        var lastCourse = 0.0

        val maxSpeedKmh = 350.0
        val maxVs = 10.0

        for (i in rows.indices) {
            val r = rows[i]

            val rollDeg = if (r.ay != null && r.az != null) {
                Math.toDegrees(atan2(r.ay, r.az))
            } else 0.0

            val pitchHud = 90.0

            if (i == 0) {
                out += FlightPoint(
                    time = "0",
                    latitude = r.lat,
                    longitude = r.lon,
                    altitude = r.alt,
                    heading = 0.0,
                    pitch = pitchHud,
                    roll = rollDeg,
                    dtSec = Double.NaN,
                    speedKmh = null,
                    vsMps = null,
                    yawDeg = 0.0,
                    timeSource = TimeSource.REAL_TIMESTAMP
                )
                continue
            }

            val prev = rows[i - 1]
            val prevLL = LatLng(prev.lat, prev.lon)
            val curLL = LatLng(r.lat, r.lon)

            val distM = distanceMeters(prevLL, curLL)
            val dt = r.tSec - prev.tSec

            val dtOk = dt.isFinite() && dt >= 0.05
            val moved = distM >= 3.0
            val notTeleport = distM <= 80.0

            val course = if (moved && notTeleport) headingDegrees(prevLL, curLL) else lastCourse
            if (moved && notTeleport) lastCourse = course

            val speedKmhVal = if (dtOk && moved && notTeleport) (distM / dt) * 3.6 else Double.NaN
            val vs = if (dtOk) (r.alt - prev.alt) / dt else Double.NaN

            out += FlightPoint(
                time = i.toString(),
                latitude = r.lat,
                longitude = r.lon,
                altitude = r.alt,
                heading = norm360(course),
                pitch = pitchHud,
                roll = rollDeg,
                dtSec = dt,
                speedKmh = speedKmhVal.takeIf { it.isFinite() && it in 1.0..maxSpeedKmh },
                vsMps = vs.takeIf { it.isFinite() && abs(it) <= maxVs },
                yawDeg = norm360(course),
                timeSource = TimeSource.REAL_TIMESTAMP
            )
        }

        Log.i(TAG, "TXT loaded: points=${out.size}, t0=${rows.first().tSec}, t1=${rows.last().tSec}")
        return out
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

        // --- kurz po trase (yaw course) – pre KML aj TXT ---
        val yawCourse = if (index > 0) {
            val prev = flightPoints[index - 1]
            headingDegrees(
                LatLng(prev.latitude, prev.longitude),
                LatLng(fp.latitude, fp.longitude)
            )
        } else {
            // pri prvom bode nemáme predchádzajúci smer -> použi heading z dát
            norm360(rawHeading)
        }

        val yawSmoothed = smoothAngle(lastYaw, yawCourse, 0.20)
        val yawNorm = norm360(yawSmoothed)
        lastYaw = yawNorm

// ✅ ZOBRAZUJ HDG z yawNorm (kurz po trase) – nech sa ti to nebije
        tvHeading.text = String.format(Locale.ROOT, "HDG: %03.0f°", yawNorm)


        // ODHAD PITCH/ROLL PRE TXT (keď log nemá tilt/roll -> 90/0)
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

        // HUD
        tvHeading.text = String.format(Locale.ROOT, "HDG: %03.0f°", headingNorm)
        val pitchDegDisplay = pitchForHud - 90.0   // ✅ reálny pitch
        val rollDegDisplay = rollForHud

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

        val rollRad = Math.toRadians(rollForHud)
        val loadFactor = 1.0 / cos(rollRad).coerceAtLeast(0.01)
        tvGForce.text = String.format(Locale.ROOT, "G: %.2f", loadFactor)

        // 3D rotation (yaw ber z trasy)
        val visualHeading = normalizeAngleDeg((-yawNorm).toFloat())

// ✅ keď je HUD pitch 0° = rovno, tak NEODČÍTAVAJ 90°
        val pitchDeg = (-pitchForHud).toFloat()   // sign si prípadne otočíme
        val rollDeg  = (rollForHud).toFloat()

        val finalRotation = Rotation(
            x = rollDeg,
            y = pitchDeg,
            z = visualHeading
        )

// scale (nechávam tvoje)
        val finalScale: Scale = if (vehicleType == VEHICLE_DRONE) {
            Scale(DRONE_SCALE)   // alebo tvoja hodnota
        } else {
            // tvoj výpočet scale podľa ALT
            val altDelta = altM - lastAltitudeM
            lastAltitudeM = altM

            val climbFactor = (altDelta / ALT_DELTA_MAX_M).coerceIn(-1.0, 1.0).toFloat()
            val targetScale = BASE_MODEL_SCALE * (1f + climbFactor * SCALE_EFFECT)
            val smoothedScale = (lastScale + (targetScale - lastScale) * SCALE_SMOOTH_ALPHA).toFloat()
            lastScale = smoothedScale.toDouble()

            Scale(smoothedScale)
        }

        if (!modelReady) {
            pendingRotation = finalRotation
        } else {
            planeNode?.rotation = finalRotation
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

            // Jednotný default pohľad “zhora” (jemne šikmo)
            cameraNode.position = Position(0f, 6.0f, 0f)
            cameraNode.rotation = Rotation(-90f, 0f, 0f)

            setOnTouchListener { _, _ -> true }
        }

        modelReady = false
        pendingRotation = null
        pendingScale = null

        val isDrone = (vehicleType == VEHICLE_DRONE)
        val modelPath = if (isDrone) "models/drone.glb" else "models/airplane_lowpoly_final.glb"

        // Blender je autorita → základ 0,0,0
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




