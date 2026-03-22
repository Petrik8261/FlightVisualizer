package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.max

/**
 * Parser pre Garmin avionické CSV záznamy (G1000 / G3X Touch).
 *
 * Štruktúra G1000 CSV:
 *   Riadok 1: informácie o lietadle (preskočíme)
 *   Riadok 2: hlavička stĺpcov
 *   Riadok 3: jednotky (ft, kt, fpm, deg) — detekujeme a preskočíme
 *   Riadok 4+: dáta, 1 Hz
 *
 * Kľúčové mapovanie:
 *   HDG   → magnetický smer nosa z AHRS
 *   TRK   → GPS track (smer pohybu nad zemou)
 *   VSpd  → barometrická VS (preferovaná)
 *   VSpdG → GPS VS (fallback ak VSpd chýba)
 *   AltB  → barometrická výška (prioritná), AltMSL/AltGPS ako fallback
 *   CRS   sa zámerne ignoruje — je to HSI selected course, nie GPS track
 */
class GarminAvionicsCsvParser(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "GarminAvionicsCsv"
        private const val MAX_SCAN_LINES_FOR_HEADER = 80

        private const val MAX_ABS_PITCH_DEG = 90.0
        private const val MAX_ABS_ROLL_DEG  = 180.0
        private const val MAX_ABS_VS_FTPM   = 10_000.0
        private const val MAX_GS_KT         = 600.0
    }

    data class FlightPointRaw(
        val timeMillis: Long?,
        val lat: Double?,
        val lon: Double?,
        val altM: Double?,
        val gsMps: Double?,
        val vsMps: Double?,
        val headingDeg: Double?,
        val trackDeg: Double?,
        val pitchDeg: Double?,
        val rollDeg: Double?,
        val iasMps: Double?,
        val tasMps: Double?,
        val sourceLine: Int
    )

    fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use {
            parseToFlightPoints(input = it, preferIasOverGs = true, fallbackDtSec = 1.0, source = LogType.GARMIN_AVIONICS)
        }
    }

    fun parse(input: InputStream): List<FlightPointRaw> {
        BufferedReader(InputStreamReader(input)).use { br ->
            val allLines = mutableListOf<String>()
            var line: String?
            while (true) { line = br.readLine() ?: break; allLines.add(line) }
            if (allLines.isEmpty()) return emptyList()

            val delimiter = detectDelimiter(allLines)
            val (headerIndex, headerCells) = findHeader(allLines, delimiter)

            if (headerIndex < 0) {
                Log.w(TAG, "Header not found.")
                return emptyList()
            }

            val unitsRowIndex = detectUnitsRow(allLines, headerIndex, headerCells, delimiter)
            val columnIndex   = buildColumnIndex(headerCells)
            val out           = ArrayList<FlightPointRaw>(allLines.size.coerceAtMost(50_000))

            for (i in headerIndex + 1 until allLines.size) {
                if (i == unitsRowIndex) continue
                val raw = allLines[i].trim()
                if (raw.isEmpty() || raw.startsWith("sep=", ignoreCase = true)) continue

                val cells = splitCsvLine(raw, delimiter)
                if (cells.isEmpty()) continue

                val p = parseRow(cells, columnIndex, i + 1) ?: continue
                out.add(p)
            }

            return out
        }
    }

    private fun detectDelimiter(lines: List<String>): Char {
        for (i in 0 until minOf(lines.size, 3)) {
            val t = lines[i].trim()
            if (t.isEmpty()) continue
            if (t.startsWith("sep=", ignoreCase = true))
                return t.substringAfter("sep=").firstOrNull() ?: ','
            break
        }
        val sample = lines.take(10).firstOrNull { it.isNotBlank() } ?: return ','
        return listOf(',', ';', '\t', '|').maxBy { c -> sample.count { it == c } }
    }

    private fun findHeader(lines: List<String>, delimiter: Char): Pair<Int, List<String>> {
        val scanMax   = minOf(lines.size, MAX_SCAN_LINES_FOR_HEADER)
        var bestIdx   = -1
        var bestScore = -1
        var bestCells = emptyList<String>()

        for (i in 0 until scanMax) {
            val row = lines[i].trim()
            if (row.isEmpty() || row.startsWith("sep=", ignoreCase = true)) continue

            val cells = splitCsvLine(row, delimiter)
            if (cells.size < 3) continue

            val score = headerScore(cells.map { normalizeHeader(it) })
            if (score > bestScore) {
                bestScore = score; bestIdx = i; bestCells = cells
            }
            if (score >= 6) break
        }

        Log.i(TAG, "Header at line=${bestIdx + 1}, score=$bestScore, cols=${bestCells.size}, delim='$delimiter'")
        return bestIdx to bestCells
    }

    /**
     * Detekuje units riadok G1000/G3X (hneď za hlavičkou).
     * Ak bunky na pozíciách lat/lon nie sú čísla (napr. obsahujú "deg"), považuje riadok za units.
     */
    private fun detectUnitsRow(
        lines: List<String>,
        headerIndex: Int,
        headerCells: List<String>,
        delimiter: Char
    ): Int {
        val candidateIdx = headerIndex + 1
        if (candidateIdx >= lines.size) return -1

        val row = lines[candidateIdx].trim()
        if (row.isEmpty()) return -1

        val cells    = splitCsvLine(row, delimiter)
        val colIndex = buildColumnIndex(headerCells)
        val latIdx   = colIndex.byField[Field.LAT]
        val lonIdx   = colIndex.byField[Field.LON]

        val latCell = latIdx?.let { if (it < cells.size) cells[it].trim() else null }
        val lonCell = lonIdx?.let { if (it < cells.size) cells[it].trim() else null }

        val latIsNumeric = latCell?.replace(",", ".")?.toDoubleOrNull() != null
        val lonIsNumeric = lonCell?.replace(",", ".")?.toDoubleOrNull() != null

        return if (!latIsNumeric && !lonIsNumeric) {
            Log.i(TAG, "Units row detected at line=${candidateIdx + 1}: lat='$latCell', lon='$lonCell'")
            candidateIdx
        } else -1
    }

    private fun headerScore(normHeaders: List<String>): Int {
        val want = setOf(
            "latitude", "lat", "longitude", "lon", "long",
            "pitch", "roll", "hdg", "heading", "trk", "track", "course",
            "ias", "tas", "gndspd", "gs", "groundspeed", "gspd",
            "vspd", "verticalspeed", "vertical_speed", "vs", "vspdg",
            "altb", "altmsl", "altgps", "altitude", "baroalt", "alt"
        )
        return normHeaders.count { it in want }
    }

    private data class ColIndex(val byField: Map<Field, Int>)

    private enum class Field {
        DATE, TIME, UTC_OFFSET, UTC_TIME, TIMESTAMP,
        LAT, LON,
        ALT_MSL, ALT_GPS, ALT_BARO, ALT_GENERIC,
        GS, IAS, TAS, VS,
        HEADING, TRACK,
        PITCH, ROLL
    }

    private fun buildColumnIndex(headerCells: List<String>): ColIndex {
        val map = mutableMapOf<Field, Int>()

        headerCells.forEachIndexed { idx, raw ->
            val h = normalizeHeader(raw)
            when (h) {
                "lcldate", "date", "localdate"                            -> map.putIfAbsent(Field.DATE, idx)
                "lcltime", "time", "localtime"                            -> map.putIfAbsent(Field.TIME, idx)
                "utcofst", "utcoffset", "utc_offset", "utcplus", "utcoff" -> map.putIfAbsent(Field.UTC_OFFSET, idx)
                "utc", "utctime"                                          -> map.putIfAbsent(Field.UTC_TIME, idx)
                "timestamp", "timeutc", "epochtime", "unix"               -> map.putIfAbsent(Field.TIMESTAMP, idx)

                "latitude", "lat"                                         -> map.putIfAbsent(Field.LAT, idx)
                "longitude", "lon", "long"                                -> map.putIfAbsent(Field.LON, idx)

                // priorita výšky: barometrická > MSL > GPS > generická
                "altb", "baroalt", "altbaro", "alt_baro", "altitude_baro" -> map.putIfAbsent(Field.ALT_BARO, idx)
                "altmsl", "mslalt", "alt_msl"                             -> map.putIfAbsent(Field.ALT_MSL, idx)
                "altgps", "gpsalt", "alt_gps"                             -> map.putIfAbsent(Field.ALT_GPS, idx)
                "altitude", "alt"                                         -> map.putIfAbsent(Field.ALT_GENERIC, idx)

                "gndspd", "groundspeed", "gs", "gspd"                    -> map.putIfAbsent(Field.GS, idx)
                "ias"                                                     -> map.putIfAbsent(Field.IAS, idx)
                "tas"                                                     -> map.putIfAbsent(Field.TAS, idx)

                // VSpd = barometrická VS, VSpdG = GPS VS (fallback)
                // putIfAbsent zabezpečí prednosť VSpd pred VSpdG
                "vspd", "verticalspeed", "vertical_speed", "vs",
                "vspdg"                                                   -> map.putIfAbsent(Field.VS, idx)

                // CRS (HSI selected course) sa zámerne ignoruje
                "hdg", "heading"                                          -> map.putIfAbsent(Field.HEADING, idx)
                "trk", "track", "course"                                  -> map.putIfAbsent(Field.TRACK, idx)

                "pitch"                                                   -> map.putIfAbsent(Field.PITCH, idx)
                "roll"                                                    -> map.putIfAbsent(Field.ROLL, idx)
            }
        }

        Log.i(TAG, "Mapped columns: $map")
        return ColIndex(map.toMap())
    }

    private fun normalizeHeader(s: String): String =
        s.trim().lowercase()
            .replace("\uFEFF", "")
            .replace("°", "")
            .replace("(deg)", "").replace("(degrees)", "")
            .replace("(kt)", "").replace("(kts)", "").replace("(knots)", "")
            .replace("(ft)", "").replace("(m)", "")
            .replace("(ft/min)", "").replace("(fpm)", "")
            .replace("[^a-z0-9_]".toRegex(), "")
            .replace("__", "_")

    private fun parseRow(cells: List<String>, col: ColIndex, sourceLine: Int): FlightPointRaw? {
        fun get(field: Field): String? {
            val i = col.byField[field] ?: return null
            return if (i in cells.indices) cells[i].trim() else null
        }

        val lat = parseDouble(get(Field.LAT)) ?: return null
        val lon = parseDouble(get(Field.LON)) ?: return null

        val timeMillis = parseTimeMillis(
            dateStr      = get(Field.DATE),
            timeStr      = get(Field.TIME),
            utcOffsetStr = get(Field.UTC_OFFSET),
            utcTimeStr   = get(Field.UTC_TIME),
            timestampStr = get(Field.TIMESTAMP)
        )

        val alt = firstNonNull(
            parseAltitudeToMeters(get(Field.ALT_BARO)),
            parseAltitudeToMeters(get(Field.ALT_MSL)),
            parseAltitudeToMeters(get(Field.ALT_GPS)),
            parseAltitudeToMeters(get(Field.ALT_GENERIC))
        )

        val gs  = parseSpeedToMps(get(Field.GS))
        val ias = parseSpeedToMps(get(Field.IAS))
        val tas = parseSpeedToMps(get(Field.TAS))
        val vs  = parseVerticalSpeedToMps(get(Field.VS))

        val heading = normalizeAngle(parseDouble(get(Field.HEADING)))
        val track   = normalizeAngle(parseDouble(get(Field.TRACK)))
        val pitch   = sanitizeAngle(parseDouble(get(Field.PITCH)), MAX_ABS_PITCH_DEG)
        val roll    = sanitizeAngle(parseDouble(get(Field.ROLL)),  MAX_ABS_ROLL_DEG)

        val gsSafe = if (gs != null && gsToKt(gs) > MAX_GS_KT) null else gs
        val vsSafe = if (vs != null && abs(msToFpm(vs)) > MAX_ABS_VS_FTPM) null else vs

        return FlightPointRaw(
            timeMillis = timeMillis, lat = lat, lon = lon, altM = alt,
            gsMps = gsSafe, vsMps = vsSafe,
            headingDeg = heading, trackDeg = track,
            pitchDeg = pitch, rollDeg = roll,
            iasMps = ias, tasMps = tas,
            sourceLine = sourceLine
        )
    }

    private fun parseTimeMillis(
        dateStr: String?, timeStr: String?,
        utcOffsetStr: String?, utcTimeStr: String?, timestampStr: String?
    ): Long? {
        parseDouble(timestampStr)?.let { ts ->
            val l = ts.toLong()
            return when {
                l > 1_000_000_000_000L -> l
                l > 1_000_000_000L     -> l * 1000L
                else                   -> null
            }
        }

        if (!utcTimeStr.isNullOrBlank()) {
            val s = utcTimeStr.trim()
            try {
                return Instant.parse(if (s.endsWith("z", true)) s else s.replace(" ", "T") + "Z")
                    .toEpochMilli()
            } catch (_: Exception) { }
        }

        if (!dateStr.isNullOrBlank() && !timeStr.isNullOrBlank()) {
            val date   = parseLocalDate(dateStr.trim()) ?: return null
            val time   = parseLocalTime(timeStr.trim()) ?: return null
            val ldt    = LocalDateTime.of(date, time)
            val offset = parseUtcOffset(utcOffsetStr)
            return ldt.toInstant(offset ?: ZoneOffset.UTC).toEpochMilli()
        }

        return null
    }

    private fun parseLocalDate(s: String): LocalDate? {
        val fmts = listOf(
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE
        )
        for (f in fmts) try { return LocalDate.parse(s, f) } catch (_: DateTimeParseException) {}
        return null
    }

    private fun parseLocalTime(s: String): LocalTime? {
        val fmts = listOf(
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm:ss.S"),
            DateTimeFormatter.ofPattern("H:mm:ss.SS"),
            DateTimeFormatter.ofPattern("H:mm:ss.SSS"),
            DateTimeFormatter.ISO_LOCAL_TIME
        )
        for (f in fmts) try { return LocalTime.parse(s, f) } catch (_: DateTimeParseException) {}
        return null
    }

    private fun parseUtcOffset(s: String?): ZoneOffset? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        parseDouble(t)?.let { hours ->
            return ZoneOffset.ofTotalSeconds((hours * 3600.0).toInt())
        }
        return try { ZoneOffset.of(t) } catch (_: Exception) { null }
    }

    private fun parseAltitudeToMeters(s: String?) = parseDouble(s)?.let { it * 0.3048 }
    private fun parseSpeedToMps(s: String?)        = parseDouble(s)?.let { it * 0.514444 }
    private fun parseVerticalSpeedToMps(s: String?)= parseDouble(s)?.let { it * 0.00508 }

    private fun gsToKt(mps: Double)  = mps / 0.514444
    private fun msToFpm(mps: Double) = mps / 0.00508

    private fun sanitizeAngle(v: Double?, maxAbs: Double) = v?.takeIf { abs(it) <= maxAbs }

    private fun normalizeAngle(v: Double?): Double? {
        if (v == null) return null
        var a = v % 360.0
        if (a < 0) a += 360.0
        return a
    }

    private fun firstNonNull(vararg v: Double?) = v.firstOrNull { it != null }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb  = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"'                -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { out.add(sb.toString()); sb.setLength(0) }
                else                   -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseDouble(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        return s.trim().replace("\uFEFF", "").replace(" ", "").replace(",", ".").toDoubleOrNull()
    }

    fun parseToFlightPoints(
        input: InputStream,
        preferIasOverGs: Boolean = true,
        fallbackDtSec: Double    = 1.0,
        source: LogType          = LogType.GARMIN_AVIONICS
    ): List<FlightPoint> {
        val rawPoints = parse(input)
        if (rawPoints.isEmpty()) return emptyList()

        val baseMs: Long? = rawPoints.firstNotNullOfOrNull { it.timeMillis }
        var lastTSec   = 0.0
        var lastAbsSec: Double? = null

        return rawPoints.mapIndexedNotNull { idx, p ->
            val lat = p.lat ?: return@mapIndexedNotNull null
            val lon = p.lon ?: return@mapIndexedNotNull null

            val absSec = p.timeMillis?.let { it / 1000.0 }
            val tSec: Double
            val dtSec: Double

            if (baseMs != null && absSec != null) {
                tSec  = absSec - baseMs / 1000.0
                dtSec = if (lastAbsSec != null) max(0.0, absSec - lastAbsSec!!) else 0.0
                lastAbsSec = absSec
                lastTSec   = tSec
            } else {
                dtSec    = if (idx == 0) 0.0 else fallbackDtSec
                tSec     = if (idx == 0) 0.0 else lastTSec + dtSec
                lastTSec = tSec
            }

            // HDG = magnetický heading z AHRS, TRK = GPS track ako fallback pre yaw
            // IAS preferujeme pred GndSpd — je to rýchlosť relevantná pre pilota
            val heading  = p.headingDeg
            val yaw      = heading ?: p.trackDeg
            val speedMps = if (preferIasOverGs) (p.iasMps ?: p.gsMps) else (p.gsMps ?: p.iasMps)

            FlightPoint(
                tSec       = tSec,
                dtSec      = dtSec,
                latitude   = lat,
                longitude  = lon,
                altitudeM  = p.altM ?: 0.0,
                speedMps   = speedMps,
                vsMps      = p.vsMps,
                pitchDeg   = p.pitchDeg,
                rollDeg    = p.rollDeg,
                yawDeg     = yaw,
                headingDeg = heading,
                source     = source
            )
        }
    }
}