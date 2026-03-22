package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.util.Locale

class MsfsCsvParser(
    private val contentResolver: ContentResolver
) : FlightParser {

    companion object {
        private const val TAG = "MsfsCsvParser"
        private const val FT_TO_M = 0.3048
    }

    override fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        val lines = input.bufferedReader().readLines()
        if (lines.size < 2) return emptyList()

        fun split(line: String) = line.split(",").map { it.trim() }
        fun toD(s: String?): Double? = s?.replace(",", ".")?.toDoubleOrNull()

        val headerLine = if (lines[0].startsWith("sep=", true) && lines.size > 1) lines[1] else lines[0]
        val dataStart  = if (lines[0].startsWith("sep=", true)) 2 else 1

        val header = split(headerLine).map { it.lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int =
            keys.firstNotNullOfOrNull { k ->
                header.indexOf(k.lowercase(Locale.ROOT)).takeIf { it >= 0 }
            } ?: -1

        val isVariantA = header.contains("time_ms") && header.contains("alt_m")
        val isVariantB = header.contains("timestamp") && header.contains("utc") && header.contains("altitude")

        if (!isVariantA && !isVariantB) {
            Log.w(TAG, "Not MSFS SkyDolly CSV. Header=${header.joinToString("|")}")
            return emptyList()
        }

        val out = ArrayList<FlightPoint>(lines.size - dataStart)
        var prevTSec: Double? = null

        if (isVariantA) {
            val iTimeMs = idx("time_ms")
            val iLat    = idx("lat_deg")
            val iLon    = idx("lon_deg")
            val iAltM   = idx("alt_m")
            val iPitch  = idx("pitch_deg")
            val iRoll   = idx("roll_deg")
            val iYaw    = idx("yaw_deg")

            if (listOf(iTimeMs, iLat, iLon, iAltM).any { it < 0 }) return emptyList()

            for (i in dataStart until lines.size) {
                val p = split(lines[i])
                if (p.size <= maxOf(iTimeMs, iLat, iLon, iAltM)) continue

                val tSec = (toD(p.getOrNull(iTimeMs)) ?: continue) / 1000.0
                val lat  = toD(p.getOrNull(iLat)) ?: continue
                val lon  = toD(p.getOrNull(iLon)) ?: continue
                val altM = toD(p.getOrNull(iAltM)) ?: continue
                val dtSec = prevTSec?.let { tSec - it } ?: Double.NaN
                prevTSec = tSec

                out += FlightPoint(
                    tSec       = tSec,
                    dtSec      = dtSec,
                    latitude   = lat,
                    longitude  = lon,
                    altitudeM  = altM,
                    speedMps   = null,
                    vsMps      = null,
                    pitchDeg   = toD(p.getOrNull(iPitch)),
                    rollDeg    = toD(p.getOrNull(iRoll)),
                    yawDeg     = toD(p.getOrNull(iYaw)),
                    headingDeg = toD(p.getOrNull(iYaw)),
                    source     = LogType.MSFS
                )
            }
        } else {
            // Variant B: Timestamp,UTC,Latitude,Longitude,Altitude,Speed,Pitch,Bank,Heading
            val iTime     = idx("timestamp")
            val iLat      = idx("latitude")
            val iLon      = idx("longitude")
            val iAltFt    = idx("altitude")
            val iSpeedKmh = idx("speed")
            val iPitch    = idx("pitch")
            val iRoll     = idx("bank")
            val iHeading  = idx("heading")

            if (listOf(iTime, iLat, iLon, iAltFt).any { it < 0 }) return emptyList()

            for (i in dataStart until lines.size) {
                val p = split(lines[i])
                if (p.size <= maxOf(iTime, iLat, iLon, iAltFt)) continue

                val tSec  = (toD(p.getOrNull(iTime)) ?: continue) / 1000.0
                val lat   = toD(p.getOrNull(iLat)) ?: continue
                val lon   = toD(p.getOrNull(iLon)) ?: continue
                val altM  = (toD(p.getOrNull(iAltFt)) ?: continue) * FT_TO_M
                val speedMps = toD(p.getOrNull(iSpeedKmh))?.let { it / 3.6 }
                val dtSec = prevTSec?.let { tSec - it } ?: Double.NaN
                prevTSec = tSec
                val hdg   = toD(p.getOrNull(iHeading))

                out += FlightPoint(
                    tSec       = tSec,
                    dtSec      = dtSec,
                    latitude   = lat,
                    longitude  = lon,
                    altitudeM  = altM,
                    speedMps   = speedMps,
                    vsMps      = null,
                    pitchDeg   = toD(p.getOrNull(iPitch)),
                    rollDeg    = toD(p.getOrNull(iRoll)),
                    yawDeg     = hdg,
                    headingDeg = hdg,
                    source     = LogType.MSFS
                )
            }
        }

        Log.i(TAG, "MSFS parsed points=${out.size}")
        return out
    }
}
