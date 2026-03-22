package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.util.Locale
import kotlin.math.abs

class TxtCsvFlightParser(
    private val contentResolver: ContentResolver
) {

    companion object { private const val TAG = "TxtCsvFlightParser" }

    fun parse(uri: Uri): List<FlightPoint> {

        val input = contentResolver.openInputStream(uri) ?: return emptyList()

        val lines = input.bufferedReader().useLines { seq ->
            seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

        if (lines.size < 2) return emptyList()

        fun splitLineSmart(line: String): List<String> =
            when {
                line.contains("\t") -> line.split(Regex("\t+"))
                line.contains(";")  -> line.split(";")
                line.contains(",")  -> line.split(",")
                else                -> line.split(Regex("\\s+"))
            }.map { it.trim() }

        fun toD(s: String?): Double? = s?.replace(",", ".")?.toDoubleOrNull()
        fun Double.isValidLat() = isFinite() && this in -90.0..90.0
        fun Double.isValidLon() = isFinite() && this in -180.0..180.0

        fun parseTimeToSec(s: String): Double? {
            val v = s.trim()
            return if (v.contains(":")) {
                val p = v.split(":")
                if (p.size != 3) null
                else {
                    val h   = p[0].toIntOrNull() ?: return null
                    val m   = p[1].toIntOrNull() ?: return null
                    val sec = p[2].toIntOrNull() ?: return null
                    (h * 3600 + m * 60 + sec).toDouble()
                }
            } else {
                v.toDoubleOrNull()?.let { if (it > 10_000.0) it / 1000.0 else it }
            }
        }

        val header = splitLineSmart(lines.first())
            .map { it.removePrefix("\uFEFF").lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k ->
                header.indexOfFirst { it == k }.takeIf { it >= 0 }
            }

        val iTime  = idx("time", "time_ms", "timestamp", "t_s")           ?: return emptyList()
        val iLat   = idx("latitude", "lat", "lat_deg")                     ?: return emptyList()
        val iLon   = idx("longitude", "lon", "lon_deg", "lng")             ?: return emptyList()
        val iAlt   = idx("altitude", "altitude (m)", "alt_m", "alt")       ?: return emptyList()
        val iSpd   = idx("speed_mps", "groundspeed_mps", "spd_mps", "speed")
        val iVs    = idx("vspeed_mps", "verticalspeed_mps", "vs_mps")
        val iPitch = idx("pitch", "pitch_deg")
        val iRoll  = idx("roll", "roll_deg", "bank", "bank_deg")
        val iYaw   = idx("yaw", "yaw_deg", "heading", "heading_deg")

        data class Raw(
            val tSec: Double,
            val lat: Double, val lon: Double, val altM: Double,
            val speedMps: Double?, val vsMps: Double?,
            val pitch: Double?, val roll: Double?, val yaw: Double?
        )

        val raw = mutableListOf<Raw>()

        for (i in 1 until lines.size) {
            val parts = splitLineSmart(lines[i])
            if (parts.size <= maxOf(iTime, iLat, iLon, iAlt)) continue

            val tSec = parseTimeToSec(parts[iTime]) ?: continue
            val lat  = toD(parts[iLat]) ?: continue
            val lon  = toD(parts[iLon]) ?: continue
            val altM = toD(parts[iAlt]) ?: continue
            if (!lat.isValidLat() || !lon.isValidLon()) continue

            raw += Raw(
                tSec     = tSec,
                lat      = lat, lon = lon, altM = altM,
                speedMps = iSpd?.let  { parts.getOrNull(it)?.let { s -> toD(s) } },
                vsMps    = iVs?.let   { parts.getOrNull(it)?.let { s -> toD(s) } },
                pitch    = iPitch?.let { parts.getOrNull(it)?.let { s -> toD(s) } },
                roll     = iRoll?.let  { parts.getOrNull(it)?.let { s -> toD(s) } },
                yaw      = iYaw?.let   { parts.getOrNull(it)?.let { s -> toD(s) } }
            )
        }

        if (raw.size < 2) return emptyList()

        val out  = mutableListOf<FlightPoint>()
        val t0   = raw.first().tSec
        var prevTSec: Double? = null
        var prevAlt:  Double? = null

        for (r in raw) {
            val tSec  = r.tSec - t0
            val dtSec = prevTSec?.let { (tSec - it).coerceAtLeast(0.001) } ?: Double.NaN

            val vsComputed =
                if (prevTSec != null && prevAlt != null)
                    ((r.altM - prevAlt!!) / (tSec - prevTSec!!)).takeIf { abs(it) < 50 }
                else null

            out += FlightPoint(
                tSec       = tSec,
                dtSec      = dtSec,
                latitude   = r.lat,
                longitude  = r.lon,
                altitudeM  = r.altM,
                speedMps   = r.speedMps,
                vsMps      = r.vsMps ?: vsComputed,
                pitchDeg   = r.pitch,
                rollDeg    = r.roll,
                yawDeg     = r.yaw,
                headingDeg = r.yaw,
                source     = LogType.GENERIC
            )

            prevTSec = tSec
            prevAlt  = r.altM
        }

        Log.i(TAG, "TXT/CSV parsed: points=${out.size}")
        return out
    }
}

