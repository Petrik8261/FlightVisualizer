package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

class ArduinoTxtParser(
    private val contentResolver: ContentResolver
) {
    companion object { private const val TAG = "ArduinoTxtParser" }

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

        fun parseHmsToSec(s: String): Double? {
            val p = s.trim().split(":")
            if (p.size != 3) return null
            val h   = p[0].toIntOrNull() ?: return null
            val m   = p[1].toIntOrNull() ?: return null
            val sec = p[2].toIntOrNull() ?: return null
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        val header = splitLineSmart(lines.first())
            .map { it.removePrefix("\uFEFF").lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k ->
                header.indexOfFirst { it == k }.takeIf { it >= 0 }
            }

        val iTime = idx("time")                                     ?: return emptyList()
        val iLat  = idx("latitude", "lat")                          ?: return emptyList()
        val iLon  = idx("longitude", "lon", "lng")                  ?: return emptyList()
        val iAlt  = idx("altitude (m)", "altitude", "alt_m", "alt") ?: return emptyList()

        val iX = idx("x")
        val iY = idx("y")
        val iZ = idx("z")

        data class RawLine(
            val tAbsSec: Double,
            val lat: Double, val lon: Double, val altM: Double,
            val ax: Double?, val ay: Double?, val az: Double?
        )

        val rawLines = mutableListOf<RawLine>()

        for (i in 1 until lines.size) {
            val parts = splitLineSmart(lines[i])
            if (parts.size <= maxOf(iTime, iLat, iLon, iAlt)) continue

            val tAbs = parseHmsToSec(parts[iTime]) ?: continue
            val lat  = toD(parts[iLat]) ?: continue
            val lon  = toD(parts[iLon]) ?: continue
            val altM = toD(parts[iAlt]) ?: continue
            if (!lat.isValidLat() || !lon.isValidLon()) continue

            val ax = iX?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val ay = iY?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val az = iZ?.takeIf { it < parts.size }?.let { toD(parts[it]) }

            rawLines += RawLine(tAbs, lat, lon, altM, ax, ay, az)
        }

        if (rawLines.size < 2) return emptyList()

        // IMU vzorkuje rýchlejšie ako GPS poskytuje nový fix — viacero riadkov zdieľa rovnakú
        // GPS pozíciu. Agregujeme ich do jednej skupiny: jeden FlightPoint na GPS fix,
        // pitch/roll z priemeru IMU vzoriek skupiny.
        data class Group(
            val tAbsSec: Double,
            val lat: Double, val lon: Double, val altM: Double,
            val axList: MutableList<Double> = mutableListOf(),
            val ayList: MutableList<Double> = mutableListOf(),
            val azList: MutableList<Double> = mutableListOf()
        )

        val groups = mutableListOf<Group>()

        for (r in rawLines) {
            val last = groups.lastOrNull()
            if (last != null && isSamePosition(last.lat, last.lon, r.lat, r.lon)) {
                r.ax?.let { last.axList += it }
                r.ay?.let { last.ayList += it }
                r.az?.let { last.azList += it }
            } else {
                val g = Group(r.tAbsSec, r.lat, r.lon, r.altM)
                r.ax?.let { g.axList += it }
                r.ay?.let { g.ayList += it }
                r.az?.let { g.azList += it }
                groups += g
            }
        }

        Log.i(TAG, "Raw lines=${rawLines.size} → groups=${groups.size} " +
                "(avg ${String.format("%.1f", rawLines.size.toDouble() / groups.size)} IMU/GPS fix)")

        if (groups.size < 2) return emptyList()

        val t0 = groups.first().tAbsSec
        val out = ArrayList<FlightPoint>(groups.size)
        var prevTSec: Double? = null
        var prevAltM: Double? = null

        for (g in groups) {
            val tSec  = g.tAbsSec - t0
            val dtSec = prevTSec?.let { (tSec - it).coerceAtLeast(0.1) } ?: Double.NaN

            val ax = g.axList.takeIf { it.isNotEmpty() }?.average()
            val ay = g.ayList.takeIf { it.isNotEmpty() }?.average()
            val az = g.azList.takeIf { it.isNotEmpty() }?.average()

            val (pitchDeg, rollDeg) =
                if (ax != null && ay != null && az != null &&
                    ax.isFinite() && ay.isFinite() && az.isFinite()) {
                    val roll  = Math.toDegrees(atan2(ay, az))
                    val pitch = Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
                    pitch to -roll
                } else {
                    null to null
                }

            val vsMps = if (prevTSec != null && prevAltM != null) {
                val dt = tSec - prevTSec!!
                if (dt > 0.0) ((g.altM - prevAltM!!) / dt).takeIf { it.isFinite() } else null
            } else null

            out += FlightPoint(
                tSec       = tSec,
                dtSec      = dtSec,
                latitude   = g.lat,
                longitude  = g.lon,
                altitudeM  = g.altM,
                speedMps   = null,
                vsMps      = vsMps,
                pitchDeg   = pitchDeg,
                rollDeg    = rollDeg,
                yawDeg     = null,
                headingDeg = null,
                source     = LogType.ARDUINO_TXT
            )

            prevTSec = tSec
            prevAltM = g.altM
        }

        Log.i(TAG, "Arduino TXT parsed: points=${out.size}, header=${header.joinToString("|")}")
        return out
    }

    /**
     * Porovnáva dve GPS pozície s toleranciou ~1 m (5 desatinných miest ≈ 1.1 m).
     */
    private fun isSamePosition(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val factor = 100_000.0
        return (Math.round(lat1 * factor) == Math.round(lat2 * factor)) &&
                (Math.round(lon1 * factor) == Math.round(lon2 * factor))
    }
}