package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.util.Locale

class DroneCsvParser(
    private val contentResolver: ContentResolver
) : FlightParser {

    companion object {
        private const val TAG = "DroneCsvParser"

        private const val FT_TO_M = 0.3048
        private const val MPH_TO_MPS = 0.44704

        // DJI zSpeed býva "down positive" -> invert
        private const val DJI_ZSPEED_DOWN_POSITIVE = true

        // ak zistíš, že pitch je opačne, prepni na true
        private const val INVERT_PITCH = false
    }

    override fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        val lines = input.bufferedReader().readLines()
        if (lines.size < 3) return emptyList()

        // DJI často má prvý riadok "sep=,"
        val headerLine = if (lines[0].startsWith("sep=", ignoreCase = true)) lines[1] else lines[0]
        val dataStartIndex = if (lines[0].startsWith("sep=", ignoreCase = true)) 2 else 1

        fun split(line: String) = line.split(",").map { it.trim() }
        fun toD(s: String?): Double? = s?.replace(",", ".")?.toDoubleOrNull()

        val header = split(headerLine).map { it.lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int =
            keys.firstNotNullOfOrNull { k ->
                header.indexOf(k.lowercase(Locale.ROOT)).takeIf { it >= 0 }
            } ?: -1

        val iTSec   = idx("osd.flytime [s]")
        val iLat    = idx("osd.latitude")
        val iLon    = idx("osd.longitude")
        val iHft    = idx("osd.height [ft]", "osd.vpsheight [ft]")
        val iAltFt  = idx("osd.altitude [ft]") // fallback
        val iSpdMph = idx("osd.hspeed [mph]")
        val iPitch  = idx("osd.pitch")
        val iRoll   = idx("osd.roll")
        val iYaw360 = idx("osd.yaw [360]")
        val iYaw    = idx("osd.yaw")
        val iZmph   = idx("osd.zspeed [mph]")

        if (listOf(iTSec, iLat, iLon).any { it < 0 }) {
            Log.w(TAG, "Missing required DJI columns (flytime/lat/lon). Header=${header.joinToString("|")}")
            return emptyList()
        }

        val out = ArrayList<FlightPoint>(lines.size - dataStartIndex)

        var prevTSec: Double? = null
        var prevAltM: Double? = null

        for (i in dataStartIndex until lines.size) {
            val p = split(lines[i])
            if (p.size <= maxOf(iTSec, iLat, iLon)) continue

            val tSec = toD(p.getOrNull(iTSec)) ?: continue
            val lat  = toD(p.getOrNull(iLat)) ?: continue
            val lon  = toD(p.getOrNull(iLon)) ?: continue

            // altitude: prefer height[ft] (AGL), fallback altitude[ft]
            val heightFt = toD(p.getOrNull(iHft))
            val altFt = heightFt ?: toD(p.getOrNull(iAltFt))
            val altM = (altFt ?: 0.0) * FT_TO_M

            // speed: hSpeed [mph] -> m/s
            val spdMps = toD(p.getOrNull(iSpdMph))
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { it * MPH_TO_MPS }

            // yaw/heading
            val yawDeg = (toD(p.getOrNull(iYaw360))?.takeIf { it.isFinite() }
                ?: toD(p.getOrNull(iYaw))?.takeIf { it.isFinite() })

            // pitch/roll
            val pitchRaw = toD(p.getOrNull(iPitch))
            val pitchDeg = pitchRaw?.let { if (INVERT_PITCH) -it else it }
            val rollDeg  = toD(p.getOrNull(iRoll))

            // dt
            val dtSec = prevTSec?.let { tSec - it } ?: Double.NaN

            // VS: primárne z altitude derivácie
            val vsFromAlt = if (prevTSec != null && prevAltM != null) {
                val dt = tSec - prevTSec!!
                if (dt > 0.0) (altM - prevAltM!!) / dt else null
            } else null

            // fallback VS: zSpeed [mph] (invert ak down-positive)
            val vsFromZ = toD(p.getOrNull(iZmph))
                ?.takeIf { it.isFinite() }
                ?.let { it * MPH_TO_MPS }
                ?.let { z -> if (DJI_ZSPEED_DOWN_POSITIVE) -z else z }

            val vsMps = vsFromAlt ?: vsFromZ

            out += FlightPoint(
                tSec = tSec,
                dtSec = dtSec,
                latitude = lat,
                longitude = lon,
                altitudeM = altM,
                speedMps = spdMps,
                vsMps = vsMps,
                pitchDeg = pitchDeg,
                rollDeg = rollDeg,
                yawDeg = yawDeg,
                headingDeg = yawDeg,
                source = LogType.DRONE
            )

            prevTSec = tSec
            prevAltM = altM
        }

        Log.i(TAG, "DJI FlightRecord parsed points=${out.size}")
        return out
    }
}

