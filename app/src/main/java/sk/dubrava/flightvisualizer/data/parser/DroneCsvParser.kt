package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class DroneCsvParser(
    private val contentResolver: ContentResolver
) : FlightParser {

    companion object {
        private const val TAG = "DroneCsvParser"

        private const val FT_TO_M = 0.3048
        private const val MPH_TO_MPS = 0.44704

        // DJI/AirData zSpeed je "down positive" — invertujeme na "up positive"
        private const val ZSPEED_DOWN_POSITIVE = true
        private const val INVERT_PITCH = false
    }

    override fun parse(uri: Uri): List<FlightPoint> {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))

                val csv = readCsvHeader(reader) ?: return emptyList()
                val delimiter = csv.delimiter

                fun splitCsv(line: String): List<String> = splitCsvQuoted(line, delimiter)
                fun toD(s: String?): Double? = s?.trim()?.replace(",", ".")?.toDoubleOrNull()

                // AirData má leading spaces v názvoch stĺpcov — trim je nevyhnutný
                val header = splitCsv(csv.headerLine).map { it.trim().lowercase(Locale.ROOT) }

                val isDji = header.any { it.startsWith("osd.") } || header.contains("osd.flytime [s]")
                val isAirData = header.contains("time(millisecond)") &&
                        header.contains("latitude") && header.contains("longitude")

                if (!isDji && !isAirData) {
                    Log.w(TAG, "Unknown UAV CSV format. Header=${header.joinToString("|")}")
                    return emptyList()
                }

                if (isDji) parseDji(reader, csv.firstDataLine, ::splitCsv, ::toD, header)
                else parseAirData(reader, csv.firstDataLine, ::splitCsv, ::toD, header)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UAV CSV: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseDji(
        reader: BufferedReader,
        firstDataLine: String?,
        splitCsv: (String) -> List<String>,
        toD: (String?) -> Double?,
        header: List<String>
    ): List<FlightPoint> {

        fun idx(vararg keys: String): Int =
            keys.firstNotNullOfOrNull { k ->
                header.indexOf(k.trim().lowercase(Locale.ROOT)).takeIf { it >= 0 }
            } ?: -1

        val iTSec  = idx("osd.flytime [s]")
        val iLat   = idx("osd.latitude")
        val iLon   = idx("osd.longitude")
        val iHft   = idx("osd.height [ft]", "osd.vpsheight [ft]")
        val iAltFt = idx("osd.altitude [ft]")
        val iSpdMph = idx("osd.hspeed [mph]")
        val iPitch = idx("osd.pitch")
        val iRoll  = idx("osd.roll")
        val iYaw360 = idx("osd.yaw [360]")
        val iYaw   = idx("osd.yaw")
        val iZmph  = idx("osd.zspeed [mph]")

        if (listOf(iTSec, iLat, iLon).any { it < 0 }) {
            Log.w(TAG, "Missing required DJI columns (flytime/lat/lon).")
            return emptyList()
        }

        val out = ArrayList<FlightPoint>(4096)
        var prevTSec: Double? = null

        var line: String? = firstDataLine ?: reader.readLine()
        while (line != null) {
            val p = splitCsv(line)
            if (p.size <= maxOf(iTSec, iLat, iLon)) { line = reader.readLine(); continue }

            val tSec = toD(p.getOrNull(iTSec))
            val lat  = toD(p.getOrNull(iLat))
            val lon  = toD(p.getOrNull(iLon))
            if (tSec == null || lat == null || lon == null) { line = reader.readLine(); continue }

            val altFt = toD(p.getOrNull(iHft)) ?: toD(p.getOrNull(iAltFt)) ?: 0.0
            val altM  = altFt * FT_TO_M

            val spdMps = toD(p.getOrNull(iSpdMph))
                ?.takeIf { it.isFinite() && it >= 0.0 }?.let { it * MPH_TO_MPS }

            val yawDeg = toD(p.getOrNull(iYaw360))?.takeIf { it.isFinite() }
                ?: toD(p.getOrNull(iYaw))?.takeIf { it.isFinite() }

            val pitchDeg = toD(p.getOrNull(iPitch))?.let { if (INVERT_PITCH) -it else it }
            val rollDeg  = toD(p.getOrNull(iRoll))

            val vsMps = toD(p.getOrNull(iZmph))?.takeIf { it.isFinite() }
                ?.let { it * MPH_TO_MPS }?.let { z -> if (ZSPEED_DOWN_POSITIVE) -z else z }

            out += FlightPoint(
                tSec       = tSec,
                dtSec      = prevTSec?.let { tSec - it } ?: Double.NaN,
                latitude   = lat,
                longitude  = lon,
                altitudeM  = altM,
                speedMps   = spdMps,
                vsMps      = vsMps,
                pitchDeg   = pitchDeg,
                rollDeg    = rollDeg,
                yawDeg     = yawDeg,
                headingDeg = yawDeg,
                source     = LogType.DRONE
            )

            prevTSec = tSec
            line = reader.readLine()
        }

        Log.i(TAG, "DJI FlightRecord parsed points=${out.size}")
        return out
    }

    private fun parseAirData(
        reader: BufferedReader,
        firstDataLine: String?,
        splitCsv: (String) -> List<String>,
        toD: (String?) -> Double?,
        header: List<String>
    ): List<FlightPoint> {

        fun idx(vararg keys: String): Int =
            keys.firstNotNullOfOrNull { k ->
                header.indexOf(k.trim().lowercase(Locale.ROOT)).takeIf { it >= 0 }
            } ?: -1

        val iTms    = idx("time(millisecond)")
        val iLat    = idx("latitude")
        val iLon    = idx("longitude")
        val iHft    = idx("height_above_takeoff(feet)")
        val iSpdMph = idx("speed(mph)")
        val iZmph   = idx("zspeed(mph)")
        val iYaw    = idx("compass_heading(degrees)")
        val iPitch  = idx("pitch(degrees)")
        val iRoll   = idx("roll(degrees)")

        if (listOf(iTms, iLat, iLon).any { it < 0 }) {
            Log.w(TAG, "Missing required AirData columns (time/lat/lon).")
            return emptyList()
        }

        val out = ArrayList<FlightPoint>(4096)
        var prevTSec: Double? = null

        var line: String? = firstDataLine ?: reader.readLine()
        while (line != null) {
            val p = splitCsv(line)
            if (p.size <= maxOf(iTms, iLat, iLon)) { line = reader.readLine(); continue }

            val tMs = toD(p.getOrNull(iTms))
            val lat = toD(p.getOrNull(iLat))
            val lon = toD(p.getOrNull(iLon))
            if (tMs == null || lat == null || lon == null) { line = reader.readLine(); continue }

            val tSec  = tMs / 1000.0
            val altM  = (toD(p.getOrNull(iHft)) ?: 0.0) * FT_TO_M

            val spdMps = toD(p.getOrNull(iSpdMph))
                ?.takeIf { it.isFinite() && it >= 0.0 }?.let { it * MPH_TO_MPS }

            val yawDeg   = toD(p.getOrNull(iYaw))?.takeIf { it.isFinite() }
            val pitchDeg = toD(p.getOrNull(iPitch))?.let { if (INVERT_PITCH) -it else it }
            val rollDeg  = toD(p.getOrNull(iRoll))

            val vsMps = toD(p.getOrNull(iZmph))?.takeIf { it.isFinite() }
                ?.let { it * MPH_TO_MPS }?.let { z -> if (ZSPEED_DOWN_POSITIVE) -z else z }

            out += FlightPoint(
                tSec       = tSec,
                dtSec      = prevTSec?.let { tSec - it } ?: Double.NaN,
                latitude   = lat,
                longitude  = lon,
                altitudeM  = altM,
                speedMps   = spdMps,
                vsMps      = vsMps,
                pitchDeg   = pitchDeg,
                rollDeg    = rollDeg,
                yawDeg     = yawDeg,
                headingDeg = yawDeg,
                source     = LogType.DRONE
            )

            prevTSec = tSec
            line = reader.readLine()
        }

        Log.i(TAG, "AirData CSV parsed points=${out.size}")
        return out
    }

    private data class CsvHeader(
        val delimiter: Char,
        val headerLine: String,
        val firstDataLine: String?
    )

    /**
     * Číta hlavičku CSV — podporuje "sep=," prefix (Excel/DJI) aj automatickú detekciu oddeľovača.
     * Vracia header riadok + prvý dátový riadok, aby sa nemusel resetovať reader.
     */
    private fun readCsvHeader(reader: BufferedReader): CsvHeader? {
        val first = reader.readLine() ?: return null
        val firstTrim = first.trim()

        return if (firstTrim.startsWith("sep=", ignoreCase = true)) {
            val delim = firstTrim.substringAfter("sep=").firstOrNull() ?: ','
            val header = reader.readLine() ?: return null
            CsvHeader(delim, header, reader.readLine())
        } else {
            val candidates = listOf(',', ';', '\t', '|')
            val delim = candidates.maxBy { c -> first.count { it == c } }
            CsvHeader(delim, first, reader.readLine())
        }
    }

    /**
     * Quote-aware CSV split — rešpektuje hodnoty v úvodzovkách obsahujúce oddeľovač,
     * podporuje escaped quotes ("" → ").
     */
    private fun splitCsvQuoted(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>(64)
        val sb  = StringBuilder(line.length)
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"'); i += 2; continue
                }
                inQuotes = !inQuotes; i++; continue
            }
            if (!inQuotes && ch == delimiter) {
                out.add(sb.toString().trim()); sb.setLength(0); i++; continue
            }
            sb.append(ch); i++
        }

        out.add(sb.toString().trim())
        return out
    }
}