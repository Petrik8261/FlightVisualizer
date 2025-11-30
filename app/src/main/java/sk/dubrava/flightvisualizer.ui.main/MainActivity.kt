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
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.data.model.FlightPoint   // uprav ak máš iný balík
import android.widget.TextView
import kotlin.math.roundToInt
import java.util.Locale

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
    private lateinit var tvAltitude: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvPitchRoll: TextView



    private var isPlaying = false
    private var currentIndex = 0
    private val playHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var playbackSpeed = 1.0 // 1x rýchlosť
    private var lastSpeedKmh = 0.0
    private var lastRoll = 0.0
    private var lastPitch = 0.0
    private var lastYaw = 0.0


    // Marker, ktorý sa hýbe po trase
    private var flightMarker: com.google.android.gms.maps.model.Marker? = null

    // -------------------------------------------------------------------------
    //  onCreate – inicializácia UI, mapy
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

        // Textové polia pre zobrazenie údajov
        tvAltitude = findViewById(R.id.tvAltitude)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvPitchRoll = findViewById(R.id.tvPitchRoll)


        // 2) Playback tlačidlá
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

                // aktualizuj údaje podľa flightPoints[currentIndex]
                updateUiForIndex(currentIndex)


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

        // inicializuj panel údajov na prvý bod
        updateUiForIndex(0)
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
    //  Aktualizácia panelu s údajmi pre daný index
    // -------------------------------------------------------------------------
    private fun updateUiForIndex(index: Int) {
        if (index < 0 || index >= flightPoints.size) return

        val fp = flightPoints[index]

        // Výška
        tvAltitude.text = "Výška: ${fp.altitude.toInt()} m"

        // Rýchlosť – spočítame z dvoch po sebe idúcich bodov (približne)
        var speedKmh = 0.0
        if (index > 0) {
            val prev = flightPoints[index - 1]

            val prevLatLng = LatLng(prev.latitude, prev.longitude)
            val curLatLng = LatLng(fp.latitude, fp.longitude)

            val distMeters = distanceMeters(prevLatLng, curLatLng)

            val tPrev = parseTimeToSeconds(prev.time)
            val tCur = parseTimeToSeconds(fp.time)
            val dt = (tCur - tPrev).coerceAtLeast(1) // nech nie je 0

            val speedMs = distMeters / dt.toDouble()
            speedKmh = speedMs * 3.6
        }

        // ak je rýchlosť prakticky 0, necháme poslednú nenulovú
        if (speedKmh > 0.1) {
            lastSpeedKmh = speedKmh
        }
        tvSpeed.text = "Rýchlosť: ${lastSpeedKmh.roundToInt()} km/h"

        // roll/pitch/yaw – ak sú veľmi blízko 0, použijeme poslednú hodnotu
        val roll = if (kotlin.math.abs(fp.x) < 0.1) lastRoll else fp.x
        val pitch = if (kotlin.math.abs(fp.y) < 0.1) lastPitch else fp.y
        val yaw = if (kotlin.math.abs(fp.z) < 0.1) lastYaw else fp.z

        lastRoll = roll
        lastPitch = pitch
        lastYaw = yaw

        tvPitchRoll.text = String.format(
            java.util.Locale.ROOT,
            "Náklon (roll): %.1f°, Klopenie (pitch): %.1f°, Yaw: %.1f°",
            roll, pitch, yaw
        )
    }

    // Jednoduchý parsing času z logu – očakáva HH:MM:SS, MM:SS alebo len sekundy
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

            // aktualizuj údaje pre aktuálny index
            updateUiForIndex(currentIndex)


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
}
