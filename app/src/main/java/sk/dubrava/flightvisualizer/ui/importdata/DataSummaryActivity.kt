package sk.dubrava.flightvisualizer.ui.importdata

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.core.DerivedMode
import sk.dubrava.flightvisualizer.core.FlightHelper
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import sk.dubrava.flightvisualizer.ui.main.MainActivity
import java.util.Locale
import kotlin.math.max

class DataSummaryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE     = "extra_mode"
        const val EXTRA_FILENAME = "extra_filename"
    }

    private lateinit var flightHelper: FlightHelper
    private lateinit var vehicleType: String
    private lateinit var uri: Uri
    private var fileName: String = "—"

    private var points: List<FlightPoint> = emptyList()
    private var autoVehicleType: String = AppNav.VEHICLE_PLANE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_summary)

        flightHelper = FlightHelper(contentResolver)

        vehicleType = intent.getStringExtra(AppNav.EXTRA_VEHICLE_TYPE) ?: AppNav.VEHICLE_PLANE
        val uriStr = intent.getStringExtra(AppNav.EXTRA_FILE_URI)
        if (uriStr.isNullOrBlank()) {
            Toast.makeText(this, "Chýba súbor (URI).", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        uri      = Uri.parse(uriStr)
        fileName = intent.getStringExtra(EXTRA_FILENAME) ?: uri.toString()

        points = runCatching { flightHelper.loadFlight(uri, DerivedMode.RAW) }.getOrElse {
            Toast.makeText(this, "Nepodarilo sa načítať záznam.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (points.size < 2) {
            Toast.makeText(this, "Záznam neobsahuje dostatok bodov.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val src = points.firstOrNull()?.source

        autoVehicleType = when (src) {
            LogType.DRONE -> AppNav.VEHICLE_DRONE
            else          -> AppNav.VEHICLE_PLANE
        }

        bindSummary(points)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val mode = selectedMode()
            val i = Intent(this, MainActivity::class.java).apply {
                putExtra(AppNav.EXTRA_VEHICLE_TYPE, autoVehicleType)
                putExtra(AppNav.EXTRA_FILE_URI, uri.toString())
                putExtra(EXTRA_MODE, mode.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Log.i("SUMMARY", "src=$src autoVehicleType=$autoVehicleType")
            startActivity(i)
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAffinity()
        }
    }

    private fun bindSummary(points: List<FlightPoint>) {
        findViewById<TextView>(R.id.tvFile).text = "Súbor: $fileName"

        val src: LogType = points.firstOrNull()?.source ?: LogType.GENERIC
        val duration = computeDurationSec(points)?.let { formatDuration(it) } ?: "—"
        findViewById<TextView>(R.id.tvMeta).text =
            "Typ: ${src.name} • Body: ${points.size} • Trvanie: $duration"

        // -------------------------------------------------------
        // Dostupnosť veličín
        // -------------------------------------------------------

        val hasTrack = points.size >= 2 &&
                points.any { it.latitude.isFinite() && it.longitude.isFinite() }
        val crsAvail = if (hasTrack) Avail.OK else Avail.NA

        val hasHdgInLog = points.any {
            (it.yawDeg?.isFinite() == true) || (it.headingDeg?.isFinite() == true)
        }
        val hdgAvail = when (src) {
            LogType.MSFS, LogType.DRONE, LogType.GARMIN_AVIONICS ->
                if (hasHdgInLog) Avail.OK else Avail.EST
            LogType.KML ->
                if (hasHdgInLog) Avail.OK else Avail.EST
            LogType.KML_TRACK, LogType.GPX ->
                Avail.EST
            else ->
                Avail.EST
        }

        val hasSpeed = points.any { it.speedMps?.isFinite() == true }
        val speedAvail = when (src) {
            LogType.MSFS, LogType.DRONE, LogType.GARMIN_AVIONICS ->
                if (hasSpeed) Avail.OK else Avail.NA
            LogType.KML, LogType.KML_TRACK ->
                if (hasTrack) Avail.EST else Avail.NA
            LogType.GPX ->
                if (hasSpeed) Avail.OK else if (hasTrack) Avail.EST else Avail.NA
            else -> Avail.NA
        }

        val hasAlt  = points.any { it.altitudeM.isFinite() }
        val altAvail = if (hasAlt) Avail.OK else Avail.NA

        val hasVs        = points.any { it.vsMps?.isFinite() == true }
        val canEstimateVs = hasAlt && hasAnyTime(points)

        val vsAvail = when (src) {
            LogType.MSFS ->
                if (canEstimateVs) Avail.EST else Avail.NA
            LogType.KML, LogType.KML_TRACK, LogType.GPX ->
                if (canEstimateVs) Avail.EST else Avail.NA
            LogType.ARDUINO_TXT ->
                if (canEstimateVs) Avail.EST else Avail.NA
            LogType.DRONE, LogType.GARMIN_AVIONICS -> when {
                hasVs         -> Avail.OK
                canEstimateVs -> Avail.EST
                else          -> Avail.NA
            }
            else -> when {
                hasVs         -> Avail.OK
                canEstimateVs -> Avail.EST
                else          -> Avail.NA
            }
        }

        val hasPitch = points.any { it.pitchDeg?.isFinite() == true }
        val hasRoll  = points.any { it.rollDeg?.isFinite()  == true }

        val pitchAvail = when (src) {
            LogType.KML, LogType.KML_TRACK, LogType.GPX -> Avail.NA
            LogType.ARDUINO_TXT ->
                if (hasPitch) Avail.EST else Avail.NA
            LogType.MSFS, LogType.DRONE, LogType.GARMIN_AVIONICS ->
                if (hasPitch) Avail.OK else Avail.NA
            else -> if (hasPitch) Avail.OK else Avail.NA
        }

        val rollAvail = when (src) {
            LogType.KML_TRACK, LogType.GPX -> Avail.NA
            LogType.ARDUINO_TXT -> if (hasRoll) Avail.EST else Avail.NA
            else                -> if (hasRoll) Avail.OK  else Avail.NA
        }

        // UI
        findViewById<TextView>(R.id.tvCrs).text   = "${mark(crsAvail)} CRS (course)"
        findViewById<TextView>(R.id.tvHdg).text   = "${mark(hdgAvail)} HDG (heading)"
        findViewById<TextView>(R.id.tvSpeed).text = "${mark(speedAvail)} SPEED"
        findViewById<TextView>(R.id.tvAlt).text   = "${mark(altAvail)} ALT"
        findViewById<TextView>(R.id.tvVs).text    = "${mark(vsAvail)} VS"
        findViewById<TextView>(R.id.tvPitch).text = "${mark(pitchAvail)} PITCH"
        findViewById<TextView>(R.id.tvRoll).text  = "${mark(rollAvail)} ROLL"

        // -------------------------------------------------------
        // Režim
        // -------------------------------------------------------
        val ext = fileName.lowercase(Locale.ROOT)
        val needsChoice = ext.endsWith(".kml") || ext.endsWith(".txt") || ext.endsWith(".gpx")

        val rg         = findViewById<RadioGroup>(R.id.rgMode)
        val rbRaw      = findViewById<RadioButton>(R.id.rbRaw)
        val rbAssisted = findViewById<RadioButton>(R.id.rbAssisted)

        val assistedUseful = (vsAvail == Avail.EST)

        val autoMode = when {
            src == LogType.MSFS && vsAvail == Avail.EST -> DerivedMode.ASSISTED
            assistedUseful                              -> DerivedMode.ASSISTED
            else                                        -> DerivedMode.RAW
        }

        if (needsChoice) {
            rg.visibility = View.VISIBLE
            if (autoMode == DerivedMode.ASSISTED) rbAssisted.isChecked = true
            else rbRaw.isChecked = true
            rg.tag = null
        } else {
            rg.visibility = View.GONE
            rg.tag = autoMode
        }

        // -------------------------------------------------------
        // Poznámky
        // -------------------------------------------------------
        val notes = mutableListOf<String>()

        if (src == LogType.KML) {
            notes += "KML Camera: roll/heading sú záznam kamery; pitch je z tilt (virtuálna kamera)."
            notes += "V RAW sa pitch nezobrazuje; v ASSISTED bude pitch prepočítaný pre potrebu vizualizácie (estimated)."
        }

        if (src == LogType.KML_TRACK) {
            notes += "GPS-only KML (SkyDemon): pitch, roll ani heading nie sú v zázname."
            notes += "HDG je odvodené z GPS trasy (CRS)."
        }

        if (src == LogType.GPX) {
            notes += "GPX (ForeFlight / Garmin Pilot / Air Navigation): GPS-only záznam."
            if (hasSpeed) notes += "Rýchlosť je dostupná zo záznamu."
            else          notes += "Rýchlosť bude dopočítaná z GPS trasy (estimated)."
            notes += "Pitch, roll ani heading nie sú v GPX záznamu. HDG je odvodené z CRS."
        }

        if (src == LogType.MSFS && vsAvail == Avail.EST) {
            notes += "VS bude dopočítané z ALT a času (estimated)."
        }

        if (src == LogType.ARDUINO_TXT) {
            notes += "PITCH/ROLL sú dopočítané zo senzorov X/Y/Z (estimated)."
            if (vsAvail == Avail.EST) notes += "VS bude dopočítané z ALT a času (estimated)."
        }

        if (hdgAvail == Avail.EST) notes += "HDG bude odvodené z trasy (CRS)."
        if (needsChoice) notes += "Pre tento formát je možné zvoliť RAW (iba dostupné) alebo ASSISTED (estimated)."

        findViewById<TextView>(R.id.tvWarnings).text =
            if (notes.isEmpty()) "Bez špeciálnych poznámok."
            else notes.joinToString(separator = "\n• ", prefix = "• ")

        findViewById<TextView>(R.id.tvModeHint).text =
            if (needsChoice) "RAW = bez odhadov • ASSISTED = doplnené estimated údaje"
            else "Režim bol zvolený automaticky podľa kvality dát."
    }

    private fun selectedMode(): DerivedMode {
        val rg = findViewById<RadioGroup>(R.id.rgMode)
        if (rg.visibility != View.VISIBLE) {
            return (rg.tag as? DerivedMode) ?: DerivedMode.RAW
        }
        val rbAssisted = findViewById<RadioButton>(R.id.rbAssisted)
        return if (rbAssisted.isChecked) DerivedMode.ASSISTED else DerivedMode.RAW
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------
    private enum class Avail { OK, EST, NA }

    private fun mark(a: Avail): String = when (a) {
        Avail.OK  -> "✅"
        Avail.EST -> "⚠️"
        Avail.NA  -> "❌"
    }

    private fun hasAnyTime(points: List<FlightPoint>): Boolean {
        val hasDt = points.any { it.dtSec.isFinite() && it.dtSec > 0.0 }
        if (hasDt) return true
        for (i in 0 until points.size - 1) {
            val a = points[i].tSec
            val b = points[i + 1].tSec
            if (a.isFinite() && b.isFinite() && b > a) return true
        }
        return false
    }

    private fun computeDurationSec(points: List<FlightPoint>): Double? {
        val first = points.first().tSec
        val last  = points.last().tSec
        if (first.isFinite() && last.isFinite() && last > first) return last - first

        var sum = 0.0
        var ok  = false
        for (p in points) {
            val dt = p.dtSec
            if (dt.isFinite() && dt > 0.0) { sum += dt; ok = true }
        }
        return if (ok) sum else null
    }

    private fun formatDuration(sec: Double): String {
        val s = max(0.0, sec).toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r)
        else String.format(Locale.US, "%02d:%02d", m, r)
    }
}