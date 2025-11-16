package sk.dubrava.flightvisualizer.ui.main

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
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
import sk.tvojemeno.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.FlightType

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // --- Map + flight data ---
    private var googleMap: GoogleMap? = null
    private val TAG = "MAP_DEBUG"

    private var flightPoints: List<FlightPoint> = emptyList()
    private var routeLatLng: List<LatLng> = emptyList()

    // --- Playback ---
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnSpeed: Button

    private var isPlaying = false
    private var currentIndex = 0
    private val playHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var playbackSpeed = 1.0 // 1x rýchlosť

    // Marker, ktorý sa hýbe po trase
    private var flightMarker: com.google.android.gms.maps.model.Marker? = null

    // --- 3D SceneView ---
    private lateinit var droneView: SceneView
    private var droneNode: ModelNode? = null

    // typ letu – zatiaľ nastavíme DRONE
    private var flightType: FlightType = FlightType.DRONE

    // -------------------------------------------------------------------------
    //  onCreate – inicializácia UI, SceneView, mapy
    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "MainActivity: onCreate")
        setContentView(R.layout.activity_main)

        // 1) UI prvky
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)

        droneView = findViewById(R.id.droneView)

        // 2) Inicializácia 3D scény a načítanie modelu
        setup3DScene()

        // 3) Playback tlačidlá
        btnPlay.setOnClickListener { startPlayback() }

        btnPause.setOnClickListener {
            // pauza – zastaví animáciu, ale nechá currentIndex aj slider tam, kde sú
            stopPlayback()
        }

        btnStop.setOnClickListener {
            stopPlayback()
            if (routeLatLng.isNotEmpty()) {
                currentIndex = 0
                playbackSeekBar.progress = 0
                val start = routeLatLng.first()
                flightMarker?.position = start
                googleMap?.moveCamera(CameraUpdateFactory.newLatLng(start))
            }
        }

        btnSpeed.setOnClickListener {
            cyclePlaybackSpeed()
        }

        // 4) Tlačidlo na zmenu typu mapy (normal/sat/hybrid/terrain)
        val mapTypeButton = findViewById<FloatingActionButton>(R.id.btnMapType)
        mapTypeButton.setOnClickListener {
            showMapTypeBottomSheet()
        }

        // 5) Google Play Services kontrola
        if (!checkGooglePlayServices()) {
            return
        }

        // 6) MapFragment
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

        // 1) Načítať body letu z raw súboru
        flightPoints = loadFlightFromRaw()

        if (flightPoints.isEmpty()) {
            Toast.makeText(this, "Žiadne body v logu 😢", Toast.LENGTH_LONG).show()
            return
        }

        // 2) LatLng trasa + čistenie od skokov
        val rawRoute = flightPoints.map { LatLng(it.latitude, it.longitude) }
        routeLatLng = cleanRoute(rawRoute, maxStepMeters = 500.0)

        if (routeLatLng.size < 2) {
            Toast.makeText(this, "Príliš málo bodov po čistení trasy", Toast.LENGTH_LONG).show()
            return
        }

        // 3) Nastavenie slidera
        playbackSeekBar.max = routeLatLng.size - 1
        playbackSeekBar.progress = 0

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (routeLatLng.isEmpty()) return

                currentIndex = progress
                val point = routeLatLng[progress]

                // posuň marker na danú pozíciu
                flightMarker?.position = point
                googleMap?.moveCamera(CameraUpdateFactory.newLatLng(point))

                // TODO: aktualizuj texty (výška, rýchlosť...) podľa flightPoints[progress]
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // pri ťahaní slidera stopneme auto prehrávanie
                stopPlayback()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // nateraz nič
            }
        })

        // 4) Vykresliť polyline trasy
        drawRoute(routeLatLng)

        // 5) Markery na začiatok/koniec
        val startPoint = routeLatLng.first()
        val startTime = flightPoints.first().time
        val endPoint = routeLatLng.last()
        val endTime = flightPoints.last().time

        flightMarker = googleMap?.addMarker(
            MarkerOptions()
                .position(startPoint)
                .title("Začiatok letu $startTime")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )

        googleMap?.addMarker(
            MarkerOptions()
                .position(endPoint)
                .title("Koniec letu $endTime")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        // kamera na začiatok
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 15f))
    }

    // -------------------------------------------------------------------------
    //  Načítanie flight logu z res/raw/flight_record.txt
    // -------------------------------------------------------------------------
    private fun loadFlightFromRaw(): List<FlightPoint> {
        val result = mutableListOf<FlightPoint>()

        val inputStream = resources.openRawResource(R.raw.flight_record)

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                if (trimmed.startsWith("Time")) return@forEach   // preskočíme hlavičku

                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size < 9) {
                    Log.w(TAG, "Preskakujem kratký riadok: $line")
                    return@forEach
                }

                try {
                    val time = parts[0]
                    val lat = parts[1].toDouble()
                    val lon = parts[2].toDouble()
                    val temp = parts[3].toDouble()
                    val pressure = parts[4].toDouble()
                    val altitude = parts[5].toDouble()
                    val x = parts[6].toDouble()
                    val y = parts[7].toDouble()
                    val z = parts[8].toDouble()

                    result += FlightPoint(
                        time = time,
                        latitude = lat,
                        longitude = lon,
                        temperature = temp,
                        pressure = pressure,
                        altitude = altitude,
                        x = x,
                        y = y,
                        z = z
                    )
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Nedá sa parsovať riadok: $line", e)
                }
            }
        }

        Log.i(TAG, "Načítaných bodov: ${result.size}")
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

        val sinLat = Math.sin(dLat / 2)
        val sinLon = Math.sin(dLon / 2)

        val aa = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon
        val c = 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa))

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
    //  Playback – spustenie / zastavenie / rýchlosť
    // -------------------------------------------------------------------------
    private fun startPlayback() {
        if (routeLatLng.isEmpty()) return
        if (isPlaying) return

        isPlaying = true
        currentIndex = playbackSeekBar.progress

        playHandler.post(playStepRunnable)
    }

    private fun stopPlayback() {
        isPlaying = false
        playHandler.removeCallbacks(playStepRunnable)
    }

    private val playStepRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying || routeLatLng.isEmpty()) return

            if (currentIndex >= routeLatLng.size) {
                isPlaying = false
                return
            }

            val point = routeLatLng[currentIndex]
            flightMarker?.position = point
            googleMap?.animateCamera(CameraUpdateFactory.newLatLng(point))

            playbackSeekBar.progress = currentIndex

            // --- ROTÁCIA 3D MODELU PODĽA DÁT ---
            if (currentIndex < flightPoints.size) {
                val fp = flightPoints[currentIndex]

                when (flightType) {
                    FlightType.DRONE -> {
                        droneNode?.rotation = Rotation(
                            x = fp.y.toFloat(),        // pitch
                            y = fp.z.toFloat(),        // yaw
                            z = fp.x.toFloat()         // roll
                        )
                    }

                    FlightType.AIRPLANE -> {
                        droneNode?.rotation = Rotation(
                            x = fp.y.toFloat() * 0.7f,
                            y = fp.z.toFloat(),
                            z = fp.x.toFloat() * 0.7f
                        )
                    }
                }
            }

            currentIndex++

            val baseDelay = 50L
            val delay = (baseDelay / playbackSpeed).toLong().coerceAtLeast(5L)

            playHandler.postDelayed(this, delay)
        }
    }

    private fun cyclePlaybackSpeed() {
        playbackSpeed = when (playbackSpeed) {
            0.5 -> 1.0
            1.0 -> 2.0
            2.0 -> 4.0
            else -> 0.5
        }

        val label = when (playbackSpeed) {
            0.5 -> "0.5x"
            1.0 -> "1x"
            2.0 -> "2x"
            4.0 -> "4x"
            else -> "${playbackSpeed}x"
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
    //  3D SCÉNA – SceneView + model
    // -------------------------------------------------------------------------
    private fun setup3DScene() {

        val modelFile = when (flightType) {
            FlightType.DRONE -> "models/drone_lowpoly.glb"
            FlightType.AIRPLANE -> "models/airplane_lowpoly.glb"
        }

        // ModelNode pre SceneView 0.9.0
        droneNode = ModelNode(
            position = Position(0f, 0f, -4f),
            rotation = Rotation(0f, 0f, 0f),
            scale = Scale(0.2f)   // BÝVA SPRÁVNE PRE GLB MODELY
        )

        droneView.apply {
            cameraNode.position = Position(0f, 0f, 5f)
            cameraNode.rotation = Rotation(0f, 0f, 0f)
        }

        droneNode?.position = Position(0f, -0.3f, 0f)



        droneView.addChild(droneNode!!)

        // 🔥 loadModelAsync pre SceneView 0.9.0
        droneNode?.loadModelAsync(
            context = this,
            lifecycle = lifecycle,
            glbFileLocation = modelFile,
            onLoaded = {
                Log.i(TAG, "✔️ 3D model načítaný")
            },
            onError = { exception ->
                Log.e(TAG, "Chyba pri načítavaní modelu", exception)
                Toast.makeText(
                    this,
                    "Chyba modelu: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

}




