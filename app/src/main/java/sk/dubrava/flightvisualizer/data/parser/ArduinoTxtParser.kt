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
                line.contains(";") -> line.split(";")
                line.contains(",") -> line.split(",")
                else -> line.split(Regex("\\s+"))
            }.map { it.trim() }

        fun toD(s: String?): Double? = s?.replace(",", ".")?.toDoubleOrNull()
        fun Double.isValidLat() = isFinite() && this in -90.0..90.0
        fun Double.isValidLon() = isFinite() && this in -180.0..180.0

        fun parseHmsToSec(s: String): Double? {
            val p = s.trim().split(":")
            if (p.size != 3) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            val sec = p[2].toIntOrNull() ?: return null
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        // header
        val header = splitLineSmart(lines.first())
            .map { it.removePrefix("\uFEFF").lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k ->
                header.indexOfFirst { it == k }.takeIf { it >= 0 }
            }

        val iTime = idx("time") ?: return emptyList()
        val iLat  = idx("latitude", "lat") ?: return emptyList()
        val iLon  = idx("longitude", "lon", "lng") ?: return emptyList()
        val iAlt  = idx("altitude (m)", "altitude", "alt_m", "alt") ?: return emptyList()

        val iX = idx("x") // accel X
        val iY = idx("y") // accel Y
        val iZ = idx("z") // accel Z

        data class Raw(
            val tAbsSec: Double,
            val lat: Double,
            val lon: Double,
            val altM: Double,
            val pitchDeg: Double?,
            val rollDeg: Double?
        )

        val raw = mutableListOf<Raw>()

        for (i in 1 until lines.size) {
            val parts = splitLineSmart(lines[i])
            if (parts.size <= maxOf(iTime, iLat, iLon, iAlt)) continue

            val tAbs = parseHmsToSec(parts[iTime]) ?: continue

            val lat = toD(parts[iLat]) ?: continue
            val lon = toD(parts[iLon]) ?: continue
            val altM = toD(parts[iAlt]) ?: continue
            if (!lat.isValidLat() || !lon.isValidLon()) continue

            val ax = iX?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val ay = iY?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val az = iZ?.takeIf { it < parts.size }?.let { toD(parts[it]) }

            val (pitchDeg, rollDeg) =
                if (ax != null && ay != null && az != null && ax.isFinite() && ay.isFinite() && az.isFinite()) {
                    val roll = Math.toDegrees(atan2(ay, az))
                    val pitch = Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
                    pitch to roll
                } else {
                    null to null
                }

            raw += Raw(
                tAbsSec = tAbs,
                lat = lat,
                lon = lon,
                altM = altM,
                pitchDeg = pitchDeg,
                rollDeg = rollDeg?.let { -it }
            )
        }

        if (raw.size < 2) return emptyList()

        // relatívny čas od 0
        val t0 = raw.first().tAbsSec

        val out = ArrayList<FlightPoint>(raw.size)
        var prevTSec: Double? = null
        var prevAltM: Double? = null

        for (i in raw.indices) {
            val r = raw[i]
            val tSec = r.tAbsSec - t0

            val dtSec = prevTSec?.let { (tSec - it).coerceAtLeast(0.001) } ?: Double.NaN

            val vsMps = if (prevTSec != null && prevAltM != null) {
                ((r.altM - prevAltM!!) / (tSec - prevTSec!!)).takeIf { it.isFinite() }
            } else null

            out += FlightPoint(
                tSec = tSec,
                dtSec = dtSec,
                latitude = r.lat,
                longitude = r.lon,
                altitudeM = r.altM,
                speedMps = null,         // Arduino log nemá speed -> necháme null
                vsMps = vsMps,            // vypočítané zo zmeny výšky
                pitchDeg = r.pitchDeg,
                rollDeg = r.rollDeg,
                yawDeg = null,            // nemáme
                headingDeg = null,        // nemáme
                source = LogType.ARDUINO_TXT
            )

            prevTSec = tSec
            prevAltM = r.altM
        }

        Log.i(TAG, "Arduino TXT parsed points=${out.size} header=${header.joinToString("|")}")
        return out
    }
}

