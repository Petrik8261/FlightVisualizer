package sk.dubrava.flightvisualizer.ui.main

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import java.util.Locale
import kotlin.math.*
import android.graphics.Color
import android.graphics.PixelFormat
import com.google.android.filament.View

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val HEADING_OFFSET_DEG = 90f   // doladí smer nosu
        private const val BASE_ROLL_DEG = 90f

        // základná rotácia, ktorá model vyrovná po exporte do glTF
        val PLANE_BASE_ROTATION = Rotation(
            x = BASE_ROLL_DEG,
            y = 0f,
            z = 0f
        )
    }


    // --- Map + flight data ---
    private var googleMap: GoogleMap? = null
    private val TAG = "MAP_DEBUG"

    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

    // --- Playback ---
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
    private val playHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var playbackSpeed = 2.0
    private var hudUpdateCounter = 0

    // "sledovací" marker (môže byť aj skrytý, nechávam pre debug)
    private var flightMarker: Marker? = null

    // --- 3D lietadlo ---
    private lateinit var planeView: SceneView
    private var planeNode: ModelNode? = null
    private var planeVisible = false

    // Smoothing stav
    private var lastSpeedKmh = 0.0
    private var lastRoll = 0.0
    private var lastPitch = 0.0
    private var lastYaw = 0.0
    private var lastHeading = 0.0

    // -------------------------------------------------------------------------
    //  onCreate – inicializácia UI, mapy, 3D scény
    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "MainActivity: onCreate")
        setContentView(R.layout.activity_main)

        // 1) UI prvky
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)

        // nové krokovacie tlačidlá (musíš mať v XML: btnStepBack, btnStepForward)
        val btnStepBack: Button = findViewById(R.id.btnStepBack)
        val btnStepForward: Button = findViewById(R.id.btnStepForward)


        tvAltitude = findViewById(R.id.tvAltitude)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvPitchRoll = findViewById(R.id.tvPitchRoll)
        tvVerticalSpeed = findViewById(R.id.tvVerticalSpeed)
        tvHeading = findViewById(R.id.tvHeading)
        tvGForce = findViewById(R.id.tvGForce)

        // 3D panel – overlay nad mapou
        planeView = findViewById(R.id.planeView)
        setup3DScene()

        // 2) Playback tlačidlá

        // ▶ / ⏸ v jednom tlačidle
        btnPlay.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        // Reset – vráti simuláciu na začiatok
        btnStop.setOnClickListener {
            resetPlayback()
        }

        // krok späť (1 frame dozadu)
        btnStepBack.setOnClickListener {
            pausePlayback()
            if (routeLatLng.isEmpty()) return@setOnClickListener

            currentIndex = (currentIndex - 1).coerceAtLeast(0)
            showFrame(currentIndex)
        }

        // krok vpred (1 frame dopredu)
        btnStepForward.setOnClickListener {
            pausePlayback()
            if (routeLatLng.isEmpty()) return@setOnClickListener

            currentIndex = (currentIndex + 1).coerceAtMost(routeLatLng.lastIndex)
            showFrame(currentIndex)
        }

        btnSpeed.setOnClickListener {
            cyclePlaybackSpeed()
        }

        // 3) Tlačidlo na zmenu typu mapy (normal/sat/hybrid/terrain)
        val mapTypeButton = findViewById<FloatingActionButton>(R.id.btnMapType)
        mapTypeButton.setOnClickListener {
            showMapTypeBottomSheet()
        }

        // 4) Google Play Services kontrola
        if (!checkGooglePlayServices()) {
            return
        }

        // 5) MapFragment
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as? SupportMapFragment

        if (mapFragment == null) {
            Log.e(TAG, "❌ MapFragment sa NENAŠIEL! Skontroluj ID v XML.")
            Toast.makeText(this, "MapFragment sa nenašiel!", Toast.LENGTH_LONG).show()
            return
        } else {
            Log.i(TAG, "✔️ MapFragment nájdený, načítavam mapu…")
            Toast.makeText(this, "Načítavam mapu…", Toast.LENGTH_SHORT).show()
        }

        mapFragment.getMapAsync(this)
    }


    // -------------------------------------------------------------------------
    //  Map callback – keď je Google mapa pripravená
    // -------------------------------------------------------------------------
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE

        Log.i(TAG, "✔️ onMapReady: Mapa je pripravená!")
        Toast.makeText(this, "Mapa pripravená 👍", Toast.LENGTH_SHORT).show()

        // 1) Načítať body letu z KML
        flightPoints = loadFlightFromKml(R.raw.zaznam_dat_letu)

        if (flightPoints.isEmpty()) {
            Toast.makeText(this, "Žiadne body v logu 😢", Toast.LENGTH_LONG).show()
            return
        }

        if (flightPoints.size < 2) {
            Toast.makeText(this, "Málo bodov v KML", Toast.LENGTH_LONG).show()
            return
        }

        checkForCrazyJumps(flightPoints)

        // 2) LatLng trasa – vezmeme všetky body
        routeLatLng = flightPoints.map { LatLng(it.latitude, it.longitude) }

        // 3) slider
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
                stopPlayback()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4) Vykresliť polyline trasy
        drawRoute(routeLatLng)

        // 5) Marker na koniec (začiatok máš cez kameru)
        val startPoint = routeLatLng.first()
        val startTime = flightPoints.first().time
        val endPoint = routeLatLng.last()
        val endTime = flightPoints.last().time

        googleMap?.addMarker(
            MarkerOptions()
                .position(endPoint)
                .title("Koniec letu $endTime")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        // prvý pohľad – kamera + HUD + 3D
        currentIndex = 0
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))
        showFrame(0)
    }

    // -------------------------------------------------------------------------
    //  Kontrola „teleportov“ v dátach
    // -------------------------------------------------------------------------
    private fun checkForCrazyJumps(
        points: List<FlightPoint>,
        thresholdMeters: Double = 50000.0 // 50 km – doladíš podľa seba
    ) {
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
                Log.w(
                    TAG,
                    "⚠️ Veľký skok v logu: ${"%.1f".format(dist)} m medzi indexom ${i - 1} a $i"
                )
            }
        }

        if (crazyFound) {
            Toast.makeText(
                this,
                "Upozornenie: v zázname sú veľké skoky (teleporty).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------------------------
    //  Načítanie flight logu z res/raw/zaznam_dat_letu.kml
    // -------------------------------------------------------------------------
    private fun loadFlightFromKml(resId: Int): List<FlightPoint> {
        val result = mutableListOf<FlightPoint>()

        val inputStream = resources.openRawResource(resId)

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)
        doc.documentElement.normalize()

        // V KML sú hodnoty v <Camera> (v rámci gx:FlyTo)
        val cameraNodes = doc.getElementsByTagName("Camera")

        for (i in 0 until cameraNodes.length) {
            val node = cameraNodes.item(i)
            if (node is org.w3c.dom.Element) {

                fun get(tag: String): Double {
                    val list = node.getElementsByTagName(tag)
                    if (list.length == 0) return 0.0
                    val text = list.item(0).textContent.trim()
                    return text.toDoubleOrNull() ?: 0.0
                }

                val lon = get("longitude")
                val lat = get("latitude")
                val alt = get("altitude")
                val heading = get("heading")
                val pitch = get("tilt")
                val roll = get("roll")

                // čas nemáme → použijeme index ako string
                result += FlightPoint(
                    time = i.toString(),
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    heading = heading,
                    pitch = pitch,
                    roll = roll
                )
            }
        }

        Log.i(TAG, "Načítaných bodov z KML: ${result.size}")
        return result
    }

    // -------------------------------------------------------------------------
    //  Čistenie trasy od „skokov“
    // -------------------------------------------------------------------------
    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0 // polomer Zeme v m
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

    private fun cleanRoute(rawPoints: List<LatLng>, maxStepMeters: Double = 500.0): List<LatLng> {
        if (rawPoints.size < 2) return rawPoints

        val cleaned = mutableListOf<LatLng>()
        cleaned += rawPoints.first()

        for (i in 1 until rawPoints.size) {
            val prev = cleaned.last()
            val cur = rawPoints[i]
            val dist = distanceMeters(prev, cur)

            if (dist <= maxStepMeters) {
                cleaned += cur
            } else {
                Log.w(TAG, "Vyhadzujem bod kvôli skoku ${"%.1f".format(dist)} m: $cur")
            }
        }

        Log.i(TAG, "Trasa pred čistením: ${rawPoints.size} bodov, po čistení: ${cleaned.size}")
        return cleaned
    }

    // -------------------------------------------------------------------------
    //  Pomocné funkcie – čas, heading, smoothing
    // -------------------------------------------------------------------------
    private fun parseTimeToSeconds(time: String): Int {
        val parts = time.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                else -> time.toIntOrNull() ?: 0
            }
        } catch (e: NumberFormatException) {
            0
        }
    }

    private fun normalizeAngleDeg(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    // Výpočet kurzu/headingu medzi dvomi bodmi (0–360°)
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

    // Exponenciálne vyhladzovanie uhla v stupňoch (0–360 / -180..180)
    private fun smoothAngle(prev: Double, new: Double, alpha: Double = 0.1): Double {
        // normalizuj rozdiel na <-180, 180>, aby 359 -> 0 neskákalo
        var diff = new - prev
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0

        // jednoduché "prev + časť rozdielu"
        return prev + diff * alpha
    }

    // -------------------------------------------------------------------------
    //  Aktualizácia HUD + 3D modelu pre daný index
    // -------------------------------------------------------------------------
    private fun updateUiForIndex(index: Int) {
        if (index < 0 || index >= flightPoints.size) return

        val fp = flightPoints[index]

        // --- ALT ---
        tvAltitude.text = "ALT: ${fp.altitude.toInt()} m"

        // surové hodnoty z KML
        val rawHeading = fp.heading
        val rawPitch = fp.pitch   // tilt
        val rawRoll = fp.roll

        // inicializácia pri prvom bode
        if (index == 0) {
            lastHeading = rawHeading
            lastPitch = rawPitch
            lastRoll = rawRoll
        }

        // smoothing (môžeš upraviť alfy)
        val headingSmoothed = smoothAngle(lastHeading, rawHeading, 0.15)
        val pitchSmoothed = smoothAngle(lastPitch, rawPitch, 0.15)
        val rollSmoothed = smoothAngle(lastRoll, rawRoll, 0.15)

        lastHeading = headingSmoothed
        lastPitch = pitchSmoothed
        lastRoll = rollSmoothed

        // HUD
        tvHeading.text = String.format(Locale.ROOT, "HDG: %03.0f°", headingSmoothed)
        tvPitchRoll.text = String.format(
            Locale.ROOT,
            "ROLL: %.1f° | PITCH: %.1f° | YAW: %.1f°",
            rollSmoothed,
            pitchSmoothed,
            0.0
        )

        // G-force z rollu
        val rollRad = Math.toRadians(rollSmoothed)
        val loadFactor = 1.0 / cos(rollRad).coerceAtLeast(0.01)
        tvGForce.text = String.format(Locale.ROOT, "G: %.2f", loadFactor)

// ----------------------------------------
// ROTÁCIA 3D MODELU
// ----------------------------------------

// heading: KML 0° = sever, 90° = východ
// SceneView rotujeme opačne → -heading
        val visualHeading = normalizeAngleDeg(
            (-headingSmoothed).toFloat() + HEADING_OFFSET_DEG
        )

// PITCH – nos hore/dole
// 90° = rovný let → posunieme tak, aby 0° = rovno
        val pitchPhysical = pitchSmoothed.toFloat() - 90f      // >0 = nos hore
// bez zosilnenia, len zachováme doterajší smer
        val pitchVisual = -pitchPhysical

// ROLL – náklon krídel
// reálna hodnota z logu, bez zosilnenia
        val rollPhysical = rollSmoothed.toFloat()
        val rollVisual = rollPhysical    // ak by sa klopil opačne, daj: -rollPhysical

        planeNode?.rotation = Rotation(
            x = PLANE_BASE_ROTATION.x + rollVisual,  // základ + dynamický roll
            y = pitchVisual,                         // pitch 1 : 1 z logu
            z = visualHeading                        // smer nosu po trase
        )


    }

    // -------------------------------------------------------------------------
    //  Jeden "frame" – presun mapy, marker, HUD + 3D model
    // -------------------------------------------------------------------------
    private fun showFrame(index: Int) {
        if (routeLatLng.isEmpty()) return
        if (index < 0 || index >= routeLatLng.size) return
        if (index >= flightPoints.size) return   // bezpečnostná poistka

        val point = routeLatLng[index]

        // Presun kamery a markeru na daný bod
        googleMap?.moveCamera(CameraUpdateFactory.newLatLng(point))
        flightMarker?.position = point

        // HUD + 3D model pre tento index
        updateUiForIndex(index)

        // slider nech ukazuje rovnaký index
        playbackSeekBar.progress = index
    }

    // -------------------------------------------------------------------------
    //  Playback – spustenie / zastavenie / rýchlosť
    // -------------------------------------------------------------------------
    private fun startPlayback() {
        if (routeLatLng.isEmpty()) return
        if (isPlaying) return

        if (!planeVisible) {
            planeNode?.isVisible = true
            planeVisible = true
        }

        isPlaying = true
        btnPlay.text = "Pause"   // alebo "⏸"

        currentIndex = playbackSeekBar.progress
        playHandler.post(playStepRunnable)
    }

    private fun pausePlayback() {
        isPlaying = false
        playHandler.removeCallbacks(playStepRunnable)
        btnPlay.text = "Play"    // alebo "▶"
    }

    // stopPlayback necháme ako alias na pauzu, kvôli starým volaniam
    private fun stopPlayback() {
        pausePlayback()
    }

    // Reset tlačidla
    private fun resetPlayback() {
        pausePlayback()
        if (routeLatLng.isNotEmpty()) {
            currentIndex = 0
            playbackSeekBar.progress = 0
            val start = routeLatLng.first()
            flightMarker?.position = start
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 15f))
            updateUiForIndex(0)
        }
    }


    private val playStepRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying || routeLatLng.isEmpty()) return

            if (currentIndex >= routeLatLng.size) {
                isPlaying = false
                return
            }

            showFrame(currentIndex)
            currentIndex++

            val delay = when (playbackSpeed) {
                -1.0 -> 1000L              // REAL TIME placeholder (1 sec per frame)
                else -> (200L / playbackSpeed).toLong().coerceAtLeast(40L)
            }

            playHandler.postDelayed(this, delay)
        }

    }

    private fun cyclePlaybackSpeed() {
        playbackSpeed = when (playbackSpeed) {
            2.0 -> 1.0     // z normálnej rýchlosti na polovičnú
            1.0 -> 2.0     // späť na normálnu (interné 2×)
            else -> 2.0
        }

        val label = when (playbackSpeed) {
            2.0 -> "1X"     // interná rýchlosť 2× = normálna
            1.0 -> "0.5X"   // interná rýchlosť 1× = polovičná
            else -> "1X"
        }

        btnSpeed.text = label
        Toast.makeText(this, "Rýchlosť prehrávania: $label", Toast.LENGTH_SHORT).show()
    }




    // -------------------------------------------------------------------------
    //  Kreslenie polyline trasy
    // -------------------------------------------------------------------------
    private fun drawRoute(points: List<LatLng>) {
        val map = googleMap ?: return
        if (points.size < 2) return

        val polylineOptions = PolylineOptions()
            .addAll(points)
            .width(6f)
            .color(0xFFFFC107.toInt()) // žltá

        map.addPolyline(polylineOptions)

        val start = points.first()
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 15f))
    }

    // -------------------------------------------------------------------------
    //  BottomSheet – typ mapy
    // -------------------------------------------------------------------------
    private fun showMapTypeBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.map_type_bottom_sheet, null)

        val btnNormal = view.findViewById<Button>(R.id.btnMapNormal)
        val btnSatellite = view.findViewById<Button>(R.id.btnMapSatellite)
        val btnHybrid = view.findViewById<Button>(R.id.btnMapHybrid)
        val btnTerrain = view.findViewById<Button>(R.id.btnMapTerrain)

        btnNormal.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_NORMAL
            dialog.dismiss()
        }

        btnSatellite.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
            dialog.dismiss()
        }

        btnHybrid.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_HYBRID
            dialog.dismiss()
        }

        btnTerrain.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_TERRAIN
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    // -------------------------------------------------------------------------
    //  Google Play Services kontrola
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
    //  3D SCÉNA – SceneView + model lietadla (overlay nad mapou)
    // -------------------------------------------------------------------------
    private fun setup3DScene() {

        planeView = findViewById(R.id.planeView)

        planeView.apply {
            // vykresľuj nad mapou
            setZOrderOnTop(true)

            // transparentné pozadie view
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.TRANSLUCENT)

            // vypneme skybox, nech nie je žiadne vlastné pozadie
            scene.skybox = null

            // vždy vyčisti color + depth na úplne priehľadnú farbu
            renderer.clearOptions = renderer.clearOptions.apply {
                clear = true
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
            }

            // miešanie s mapou (translucent)
            view.blendMode = View.BlendMode.TRANSLUCENT

            // kamera – mierne zhora
            cameraNode.position = Position(0f, 0f, 4.0f)
            cameraNode.rotation = Rotation(0f, 0f, 0f)

            // zakáž ručné otáčanie/zoomovanie modelu
            setOnTouchListener { _, _ -> true }
        }

        // uzol s lietadlom – stred scény, žiadne offsety
        planeNode = ModelNode(
            position = Position(0f, 0f, 0f),
            rotation = PLANE_BASE_ROTATION,
            scale = Scale(0.03f)   // podľa potreby môžeš zmenšiť napr. na 0.3f
        ).apply {
            isVisible = true  // ukážeme až pri PLAY
        }
        planeVisible = true

        planeView.addChild(planeNode!!)

        // načítaj GLB model – názov podľa toho, ako si ho exportoval z Blenderu
        planeNode!!.loadModelAsync(
            context = this,
            lifecycle = lifecycle,
            glbFileLocation = "models/airplane_lowpoly_final.glb",
            onLoaded = {
                Log.i(TAG, "✔️ 3D model lietadla načítaný (airplane_lowpoly_final.glb)")
            },
            onError = { exception ->
                Log.e(TAG, "Chyba pri načítaní 3D modelu", exception)
                Toast.makeText(
                    this,
                    "Chyba modelu: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}

