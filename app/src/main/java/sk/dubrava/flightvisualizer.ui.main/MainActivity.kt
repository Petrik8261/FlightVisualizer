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

        // orientácia modelu
        private const val HEADING_OFFSET_DEG = 90f
        private const val BASE_ROLL_DEG = 90f

        // scale efekt podľa zmeny ALT
        private const val BASE_MODEL_SCALE = 0.03f
        private const val SCALE_EFFECT = 0.18f
        private const val SCALE_SMOOTH_ALPHA = 0.18f
        private const val ALT_DELTA_MAX_M = 3.0f

        // --- SANITY / QUALITY THRESHOLDS ---
        private const val MIN_DT_SEC = 0.05
        private const val MAX_STEP_DIST_M = 2000.0
        private const val MAX_SPEED_KMH = 250.0
        private const val MAX_VS_MPS = 30.0

        // --- TXT/CSV parsing + cleaning ---
        private const val TXT_MAX_STEP_DIST_M = 500.0      // teleport filter pre Arduino log (doladíš)
        private const val TXT_MIN_DT_SEC = 0.05           // min dt, inak N/A
        private const val TXT_MAX_SPEED_KMH = 350.0       // sanity pre speed
        private const val TXT_MAX_VS_MPS = 30.0           // sanity pre VS


        // či povolíme zobrazovať speed odhadovanú z KML Tour (gx:duration)
        private const val ALLOW_ESTIMATED_SPEED_FROM_KML_TOUR = false

        const val EXTRA_FILE_URI = "extra_file_uri"

        val PLANE_BASE_ROTATION = Rotation(
            x = BASE_ROLL_DEG,
            y = 0f,
            z = 0f
        )
    }

    private var googleMap: GoogleMap? = null
    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

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
    private var planeVisible = false

    // smoothing stav
    private var lastRoll = 0.0
    private var lastPitch = 0.0
    private var lastHeading = 0.0
    private var lastYaw = 0.0

    private var lastScale = BASE_MODEL_SCALE.toDouble()
    private var lastAltitudeM = Double.NaN

    private fun Double.isValidLat() = this.isFinite() && this in -90.0..90.0
    private fun Double.isValidLon() = this.isFinite() && this in -180.0..180.0

    private fun parseTimeToSecondsSafe(s: String): Double? {

        val t = s.trim()
        if (t.isEmpty()) return null

        t.toDoubleOrNull()?.let { return it }

        val parts = t.split(":")
        if (parts.size < 2) return null

        fun p(x: String): Double = x.toDoubleOrNull() ?: 0.0

        return when (parts.size) {
            2 -> p(parts[0]) * 60.0 + p(parts[1])
            3 -> p(parts[0]) * 3600.0 + p(parts[1]) * 60.0 + p(parts[2])
            else -> null
        }
    }

    private fun sanitizeHeading360(deg: Double): Double {
        var a = deg % 360.0
        if (a < 0) a += 360.0
        return a
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }

    private fun clamp(v: Double, min: Double, max: Double): Double = max(min, min(v, max))

    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity: onCreate")
        setContentView(R.layout.activity_main)

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
            Log.e(TAG, "❌ MapFragment sa NENAŠIEL! Skontroluj ID v XML.")
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

        checkForCrazyJumps(flightPoints)

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

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                pausePlayback()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        drawRoute(routeLatLng)

        val startPoint = routeLatLng.first()
        val endPoint = routeLatLng.last()

        googleMap?.addMarker(
            MarkerOptions()
                .position(endPoint)
                .title("Koniec letu")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))
        showFrame(0)
    }

    // -------------------------------------------------------------------------
    // Teleport check
    // -------------------------------------------------------------------------
    private fun checkForCrazyJumps(points: List<FlightPoint>, thresholdMeters: Double = 50000.0) {
        if (points.size < 2) return
        var crazyFound = false

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val dist = distanceMeters(
                LatLng(prev.latitude, prev.longitude),
                LatLng(cur.latitude, cur.longitude)
            )
            if (dist > thresholdMeters) {
                crazyFound = true
                Log.w(TAG, "⚠️ Veľký skok: ${"%.1f".format(dist)} m medzi ${i - 1} a $i")
            }
        }

        if (crazyFound) {
            Toast.makeText(this, "Upozornenie: v zázname sú veľké skoky.", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // LOAD FROM URI (dispatcher)
    // -------------------------------------------------------------------------
    private fun loadFlightFromUri(uri: android.net.Uri): List<FlightPoint> {
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

    // -------------------------------------------------------------------------
    // KML FROM URI
    // -------------------------------------------------------------------------
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
                    val pitch = node.getDouble("tilt")
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

            // segment i-1 -> i (dt = raw[i].dtSec)
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
                        yawDeg = fp.heading, // v KML je yaw ≈ heading
                        vsMps = if (vsOk) vs else null,
                        speedKmh = if (spdOk) spd else null
                    )
                }
            }
        }
    }


    private fun loadFlightFromTxtUri(uri: android.net.Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()

        val lines = input.bufferedReader().useLines { seq ->
            seq.map { it.trimEnd() }   // dôležité: nechaj začiatok, ale odstráň konce
                .filter { it.isNotBlank() }
                .toList()
        }
        if (lines.isEmpty()) return emptyList()

        // -----------------------------
        // Helpers (lokálne, aby nič nechýbalo)
        // -----------------------------
        fun splitLineSmart(line: String): List<String> {
            val t = line.trim()
            return when {
                t.contains("\t") -> t.split(Regex("\t+")).map { it.trim() }          // TAB-friendly
                t.contains(",")  -> t.split(",").map { it.trim() }                   // CSV
                else             -> t.split(Regex("\\s+")).map { it.trim() }         // fallback
            }.filter { it.isNotEmpty() }
        }

        fun toD(s: String?): Double? =
            s?.trim()
                ?.replace(",", ".")
                ?.toDoubleOrNull()

        fun Double.isValidLat() = this in -90.0..90.0
        fun Double.isValidLon() = this in -180.0..180.0

        fun parseTimeToSecondsSafe(time: String): Double? {
            val t = time.trim()
            val parts = t.split(":")
            return try {
                when (parts.size) {
                    3 -> (parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()).toDouble()
                    2 -> (parts[0].toInt() * 60 + parts[1].toInt()).toDouble()
                    else -> t.toDoubleOrNull()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun sanitizeHeading360(deg: Double): Double {
            var a = deg % 360.0
            if (a < 0) a += 360.0
            return a
        }


        val TXT_MIN_DT_SEC = 1.0            // po merge bude typicky 1+ sek
        val TXT_MAX_STEP_DIST_M = 2000.0    // max skok medzi bodmi (po merge)
        val TXT_MAX_SPEED_KMH = 350.0       // “rozumné” pre hobby/ultralight
        val TXT_MAX_VS_MPS = 30.0


        val headerTokens = splitLineSmart(lines.first())

        val hasHeader = headerTokens.any { it.equals("Latitude", true) } &&
                headerTokens.any { it.equals("Longitude", true) }


        val idx = mutableMapOf<String, Int>()
        var startIdx = 0

        fun normHeader(s: String) = s.trim().lowercase(Locale.ROOT)

        if (hasHeader) {
            headerTokens.forEachIndexed { i, name ->
                idx[normHeader(name)] = i
            }
            startIdx = 1
        } else {

            idx["time"] = 0
            idx["latitude"] = 1
            idx["longitude"] = 2

            idx["altitude (m)"] = 5
            idx["altitude"] = 5
            startIdx = 0
        }

        fun findIndexByPossibleKeys(vararg keys: String): Int? {

            for (k in keys) {
                val i = idx[normHeader(k)]
                if (i != null) return i
            }

            if (keys.any { it.contains("altitude", true) }) {
                val altKey = idx.keys.firstOrNull { it.contains("altitude") }
                if (altKey != null) return idx[altKey]
            }
            return null
        }

        fun tok(tokens: List<String>, index: Int?): String? =
            if (index == null) null else tokens.getOrNull(index)

        val iTime = findIndexByPossibleKeys("Time", "time")
        val iLat  = findIndexByPossibleKeys("Latitude", "lat", "latitude")
        val iLon  = findIndexByPossibleKeys("Longitude", "lon", "longitude")
        val iAlt  = findIndexByPossibleKeys("Altitude (m)", "Altitude", "altitude (m)", "altitude")


        data class RawRow(val time: String, val lat: Double, val lon: Double, val alt: Double)
        val rawRows = mutableListOf<RawRow>()

        for (i in startIdx until lines.size) {
            val tokens = splitLineSmart(lines[i])
            if (tokens.size < 3) continue

            val timeStr = tok(tokens, iTime) ?: tokens.firstOrNull() ?: i.toString()
            val lat = toD(tok(tokens, iLat)) ?: toD(tokens.getOrNull(1))
            val lon = toD(tok(tokens, iLon)) ?: toD(tokens.getOrNull(2))


            val alt = toD(tok(tokens, iAlt)) ?: 0.0

            if (lat == null || lon == null) continue
            if (!lat.isValidLat() || !lon.isValidLon()) continue

            rawRows += RawRow(timeStr, lat, lon, alt)
        }

        if (rawRows.size < 2) return emptyList()


        val grouped = rawRows.groupBy { it.time.trim() }
        val orderedTimes = rawRows.map { it.time.trim() }.distinct()

        val merged = orderedTimes.mapNotNull { t ->
            val rows = grouped[t] ?: return@mapNotNull null
            val latAvg = rows.map { it.lat }.average()
            val lonAvg = rows.map { it.lon }.average()
            val altAvg = rows.map { it.alt }.average()

            FlightPoint(
                time = t,
                latitude = latAvg,
                longitude = lonAvg,
                altitude = altAvg,
                heading = 0.0,
                pitch = 90.0,
                roll = 0.0,
                dtSec = Double.NaN,
                speedKmh = null,
                vsMps = null,
                yawDeg = null,
                timeSource = sk.dubrava.flightvisualizer.data.model.TimeSource.REAL_TIMESTAMP
            )
        }

        if (merged.size < 2) return merged


        val cleaned = mutableListOf<FlightPoint>()
        cleaned += merged.first()

        for (i in 1 until merged.size) {
            val prev = cleaned.last()
            val cur = merged[i]
            val dist = distanceMeters(
                LatLng(prev.latitude, prev.longitude),
                LatLng(cur.latitude, cur.longitude)
            )

            if (dist.isFinite() && dist <= TXT_MAX_STEP_DIST_M) {
                cleaned += cur
            } else {
                Log.w(TAG, "TXT: vyhadzujem bod kvôli skoku ${"%.1f".format(dist)} m (time=${cur.time})")
            }
        }

        if (cleaned.size < 2) return cleaned


        val out = cleaned.mapIndexed { idxPoint, fp ->
            if (idxPoint == 0) {
                fp.copy(
                    heading = 0.0,
                    yawDeg = 0.0,
                    speedKmh = null,
                    vsMps = null,
                    dtSec = Double.NaN
                )
            } else {
                val prev = cleaned[idxPoint - 1]

                val tPrev = parseTimeToSecondsSafe(prev.time)
                val tCur = parseTimeToSecondsSafe(fp.time)
                val dt = if (tPrev != null && tCur != null) (tCur - tPrev) else Double.NaN

                val distM = distanceMeters(
                    LatLng(prev.latitude, prev.longitude),
                    LatLng(fp.latitude, fp.longitude)
                )

                val course = headingDegrees(
                    LatLng(prev.latitude, prev.longitude),
                    LatLng(fp.latitude, fp.longitude)
                )

                val dtOk = dt.isFinite() && dt >= TXT_MIN_DT_SEC
                val speed = if (dtOk) (distM / dt) * 3.6 else Double.NaN
                val vs = if (dtOk) (fp.altitude - prev.altitude) / dt else Double.NaN

                val speedOk = speed.isFinite() && speed in 0.0..TXT_MAX_SPEED_KMH
                val vsOk = vs.isFinite() && kotlin.math.abs(vs) <= TXT_MAX_VS_MPS

                fp.copy(
                    dtSec = dt,
                    heading = sanitizeHeading360(course),
                    yawDeg = sanitizeHeading360(course),
                    speedKmh = if (speedOk) speed else null,
                    vsMps = if (vsOk) vs else null
                )
            }
        }

        Log.i(TAG, "TXT: rawRows=${rawRows.size}, merged=${merged.size}, cleaned=${cleaned.size}")

        // debug: over si, že ALT sedí (prvý/posledný)
        Log.i(TAG, "TXT first: t=${out.first().time} lat=${out.first().latitude} lon=${out.first().longitude} alt=${out.first().altitude}")
        Log.i(TAG, "TXT last : t=${out.last().time}  lat=${out.last().latitude}  lon=${out.last().longitude}  alt=${out.last().altitude}")

        return out
    }




    // -------------------------------------------------------------------------
    // Distance + headings + smoothing helpers
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // HUD + 3D update
    // -------------------------------------------------------------------------
    private fun updateUiForIndex(index: Int) {
        if (index !in flightPoints.indices) return
        val fp = flightPoints[index]

        val altM = fp.altitude
        if (index == 0 || lastAltitudeM.isNaN()) {
            lastAltitudeM = altM
            lastScale = BASE_MODEL_SCALE.toDouble()
        }

        tvAltitude.text = "ALT: ${altM.toInt()} m"

        // surové hodnoty (KML má reálne, TXT má defaulty)
        val rawHeading = fp.heading
        val rawPitch = fp.pitch
        val rawRoll = fp.roll

        if (index == 0) {
            lastHeading = norm360(rawHeading)
            lastPitch = rawPitch
            lastRoll = rawRoll
            lastYaw = norm360(rawHeading)
        }

// smoothing
        val headingSmoothed = smoothAngle(lastHeading, rawHeading, 0.15)
        val pitchSmoothed = smoothAngle(lastPitch, rawPitch, 0.15)
        val rollSmoothed = smoothAngle(lastRoll, rawRoll, 0.15)

// !!! FIX: normalizuj po smoothingu, aby HDG nešlo nad 360
        val headingNorm = norm360(headingSmoothed)
        lastHeading = headingNorm
        lastPitch = pitchSmoothed
        lastRoll = rollSmoothed

// kurz po trase (yaw course) – pre KML aj TXT
        val yawCourse = if (index > 0) {
            val prev = flightPoints[index - 1]
            headingDegrees(
                LatLng(prev.latitude, prev.longitude),
                LatLng(fp.latitude, fp.longitude)
            )
        } else headingNorm

        val yawSmoothed = smoothAngle(lastYaw, yawCourse, 0.20)
        val yawNorm = norm360(yawSmoothed)
        lastYaw = yawNorm

// ----------------------------
// ODHAD PITCH/ROLL PRE TXT
// (keď log nemá tilt/roll -> máš 90/0)
// ----------------------------
        val isTxtDefaultAttitude = (fp.pitch == 90.0 && fp.roll == 0.0)

        var rollForHud = rollSmoothed
        var pitchForHud = pitchSmoothed

        if (isTxtDefaultAttitude) {

            // --- PITCH odhad (ako doteraz) ---
            val vs = fp.vsMps
            val spdHud = fp.speedKmh
            if (vs != null && spdHud != null && spdHud > 1.0) {
                val v = spdHud / 3.6
                val gammaDeg = Math.toDegrees(atan2(vs, v))
                val gammaClamped = clamp(gammaDeg, -20.0, 20.0)
                pitchForHud = 90.0 + gammaClamped
            }

            // --- ROLL odhad (opravené): porovnaj kurz (i-2→i-1) vs (i-1→i) ---
            if (index >= 2) {
                val p0 = flightPoints[index - 2]
                val p1 = flightPoints[index - 1]
                val p2 = flightPoints[index]

                val t0 = parseTimeToSecondsSafe(p1.time)  // dt medzi p1 a p2
                val t1 = parseTimeToSecondsSafe(p2.time)
                val dt = if (t0 != null && t1 != null) (t1 - t0).toDouble() else Double.NaN

                if (dt.isFinite() && dt >= 0.5) {

                    val coursePrev = headingDegrees(
                        LatLng(p0.latitude, p0.longitude),
                        LatLng(p1.latitude, p1.longitude)
                    )
                    val courseNow = headingDegrees(
                        LatLng(p1.latitude, p1.longitude),
                        LatLng(p2.latitude, p2.longitude)
                    )

                    val dCourseDeg = angleDiffDeg(courseNow, coursePrev) // -180..180
                    val omega = Math.toRadians(dCourseDeg) / dt          // rad/s

                    // rýchlosť pre bank odhad: použijeme RAW speed z dist/dt (nezávisle od HUD filtra)
                    val distM = distanceMeters(
                        LatLng(p1.latitude, p1.longitude),
                        LatLng(p2.latitude, p2.longitude)
                    )
                    val v = if (distM.isFinite()) distM / dt else 0.0    // m/s

                    // ochrana pred šumom: ak takmer stojí, alebo dCourse extrémne malé -> nechaj 0
                    if (v > 1.0 && kotlin.math.abs(dCourseDeg) > 0.2) {
                        val g = 9.80665
                        val bankRad = atan((v * omega) / g)
                        val bankDeg = Math.toDegrees(bankRad)
                        rollForHud = clamp(bankDeg, -60.0, 60.0)
                    }
                }
            }
        }


// HUD
        tvHeading.text = String.format(Locale.ROOT, "HDG: %03.0f°", headingNorm)

        tvPitchRoll.text = String.format(
            Locale.ROOT,
            "ROLL: %.1f° | PITCH: %.1f° | YAW: %.1f°",
            rollForHud,
            pitchForHud,
            yawNorm
        )

// SPD / VS
        tvSpeed.text = if (fp.speedKmh != null) {
            String.format(Locale.ROOT, "SPD: %.0f km/h", fp.speedKmh)
        } else "SPD: N/A"

        tvVerticalSpeed.text = if (fp.vsMps != null) {
            String.format(Locale.ROOT, "VS: %.1f m/s", fp.vsMps)
        } else "VS: N/A"


        val rollRad = Math.toRadians(rollSmoothed)
        val loadFactor = 1.0 / cos(rollRad).coerceAtLeast(0.01)
        tvGForce.text = String.format(Locale.ROOT, "G: %.2f", loadFactor)

        // 3D rotation
        val visualHeading = normalizeAngleDeg(((-headingSmoothed).toFloat() + HEADING_OFFSET_DEG))

        val pitchPhysical = pitchForHud.toFloat() - 90f
        val rollPhysical = rollForHud.toFloat()

        val pitchVisual = -pitchPhysical

        val rollVisual = rollSmoothed.toFloat()

        // scale podľa ΔALT
        val altDelta = altM - lastAltitudeM
        lastAltitudeM = altM

        val climbFactor = (altDelta / ALT_DELTA_MAX_M)
            .coerceIn(-1.0, 1.0)
            .toFloat()

        val targetScale = BASE_MODEL_SCALE * (1f + climbFactor * SCALE_EFFECT)
        val smoothedScale = (lastScale + (targetScale - lastScale) * SCALE_SMOOTH_ALPHA).toFloat()
        lastScale = smoothedScale.toDouble()

        planeNode?.scale = Scale(smoothedScale)

        planeNode?.rotation = Rotation(
            x = PLANE_BASE_ROTATION.x + rollVisual,
            y = pitchVisual,
            z = visualHeading
        )
    }

    // -------------------------------------------------------------------------
    // Frame update
    // -------------------------------------------------------------------------
    private fun showFrame(index: Int) {
        if (routeLatLng.isEmpty()) return
        if (index !in routeLatLng.indices) return
        if (index !in flightPoints.indices) return

        val point = routeLatLng[index]
        googleMap?.moveCamera(CameraUpdateFactory.newLatLng(point))
        flightMarker?.position = point

        updateUiForIndex(index)
        playbackSeekBar.progress = index
    }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------
    private fun startPlayback() {
        if (routeLatLng.isEmpty()) return
        if (isPlaying) return

        if (!planeVisible) {
            planeNode?.isVisible = true
            planeVisible = true
        }

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

    // -------------------------------------------------------------------------
    // Polyline
    // -------------------------------------------------------------------------
    private fun drawRoute(points: List<LatLng>) {
        val map = googleMap ?: return
        if (points.size < 2) return

        val polylineOptions = PolylineOptions()
            .addAll(points)
            .width(6f)
            .color(0xFFFFC107.toInt())

        map.addPolyline(polylineOptions)
    }

    // -------------------------------------------------------------------------
    // Map type bottom sheet
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Google Play Services
    // -------------------------------------------------------------------------
    private fun checkGooglePlayServices(): Boolean {
        val api = GoogleApiAvailability.getInstance()
        val result = api.isGooglePlayServicesAvailable(this)

        return if (result == ConnectionResult.SUCCESS) {
            Log.i(TAG, "✔️ Google Play Services OK")
            true
        } else {
            val message = api.getErrorString(result)
            Log.e(TAG, "❌ Google Play Services error: $message")
            Toast.makeText(this, "Chyba Google Play Services: $message", Toast.LENGTH_LONG).show()
            false
        }
    }

    // -------------------------------------------------------------------------
    // 3D setup
    // -------------------------------------------------------------------------
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

            cameraNode.position = Position(0f, 0f, 4.0f)
            cameraNode.rotation = Rotation(0f, 0f, 0f)

            setOnTouchListener { _, _ -> true }
        }

        planeNode = ModelNode(
            position = Position(0f, -0.02f, 0f),
            rotation = PLANE_BASE_ROTATION,
            scale = Scale(BASE_MODEL_SCALE)
        ).apply {
            isVisible = true
        }

        planeVisible = true
        lastScale = BASE_MODEL_SCALE.toDouble()

        planeView.addChild(planeNode!!)

        planeNode!!.loadModelAsync(
            context = this,
            lifecycle = lifecycle,
            glbFileLocation = "models/airplane_lowpoly_final.glb",
            onLoaded = { Log.i(TAG, "✔️ 3D model načítaný") },
            onError = { e ->
                Log.e(TAG, "Chyba pri načítaní 3D modelu", e)
                Toast.makeText(this, "Chyba modelu: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}



