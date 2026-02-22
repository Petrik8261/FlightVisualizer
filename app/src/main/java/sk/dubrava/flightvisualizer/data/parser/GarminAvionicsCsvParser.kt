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
 * Tolerantný parser pre Garmin avionické CSV (G1000 / G3X).
 *
 * - preskočí prologue riadky
 * - nájde hlavičku automaticky
 * - mapuje rôzne názvy stĺpcov na jednotné polia
 * - vie vrátiť RAW body aj priamo FlightPoint (štýl ako ostatné parsery)
 */
class GarminAvionicsCsvParser(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "GarminAvionicsCsv"
        private const val MAX_SCAN_LINES_FOR_HEADER = 80

        private const val MAX_ABS_PITCH_DEG = 90.0
        private const val MAX_ABS_ROLL_DEG = 180.0
        private const val MAX_ABS_VS_FTPM = 10000.0 // ft/min
        private const val MAX_GS_KT = 600.0
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

    /**
     * ✅ Štandardný vstup ako ostatné parsery v projekte
     */
    fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use {
            parseToFlightPoints(
                input = it,
                preferIasOverGs = true,
                fallbackDtSec = 1.0,
                source = LogType.GARMIN_AVIONICS
            )
        }
    }

    /**
     * RAW parse (ponechané kvôli debug/analýze)
     */
    fun parse(input: InputStream): List<FlightPointRaw> {
        BufferedReader(InputStreamReader(input)).use { br ->
            val allLines = mutableListOf<String>()
            var line: String?
            while (true) {
                line = br.readLine() ?: break
                allLines.add(line)
            }
            if (allLines.isEmpty()) return emptyList()

            // delimiter: podpor "sep=;" a zároveň sniff
            val delimiter = detectDelimiterAndMaybeSkipSep(allLines)

            val (headerIndex, headerCells) = findHeader(allLines, delimiter)
            if (headerIndex < 0) {
                Log.w(TAG, "Header not found.")
                return emptyList()
            }

            val columnIndex = buildColumnIndex(headerCells)

            val out = ArrayList<FlightPointRaw>(allLines.size.coerceAtMost(50_000))
            for (i in headerIndex + 1 until allLines.size) {
                val raw = allLines[i].trim()
                if (raw.isEmpty()) continue
                if (raw.startsWith("sep=", ignoreCase = true)) continue

                val cells = splitCsvLine(raw, delimiter)
                if (cells.isEmpty()) continue

                val p = parseRow(
                    cells = cells,
                    col = columnIndex,
                    sourceLine = i + 1
                ) ?: continue

                out.add(p)
            }

            return out
        }
    }

    // ---------- delimiter detection ----------

    private fun detectDelimiterAndMaybeSkipSep(lines: List<String>): Char {
        // ak je hneď prvý meaningful riadok "sep=;", použi ho
        for (i in 0 until minOf(lines.size, 3)) {
            val t = lines[i].trim()
            if (t.isEmpty()) continue
            if (t.startsWith("sep=", ignoreCase = true)) {
                return t.substringAfter("sep=").firstOrNull() ?: ','
            }
            break
        }

        // inak sniff na prvých pár riadkoch
        val sample = lines.take(10).firstOrNull { it.isNotBlank() } ?: return ','
        val candidates = listOf(',', ';', '\t', '|')
        return candidates.maxBy { c -> sample.count { it == c } }
    }

    // ---------- Header detection ----------

    private fun findHeader(lines: List<String>, delimiter: Char): Pair<Int, List<String>> {
        val scanMax = minOf(lines.size, MAX_SCAN_LINES_FOR_HEADER)
        var bestIdx = -1
        var bestScore = -1
        var bestCells: List<String> = emptyList()

        for (i in 0 until scanMax) {
            val row = lines[i].trim()
            if (row.isEmpty()) continue
            if (row.startsWith("sep=", ignoreCase = true)) continue

            val cells = splitCsvLine(row, delimiter)
            if (cells.size < 3) continue

            val norm = cells.map { normalizeHeader(it) }
            val score = headerScore(norm)

            if (score > bestScore) {
                bestScore = score
                bestIdx = i
                bestCells = cells
            }

            if (score >= 6) break
        }

        Log.i(TAG, "Header candidate at line=${bestIdx + 1}, score=$bestScore, cells=${bestCells.size}, delim='$delimiter'")
        return bestIdx to bestCells
    }

    private fun headerScore(normHeaders: List<String>): Int {
        val want = setOf(
            "latitude", "lat",
            "longitude", "lon", "long",
            "pitch", "roll",
            "hdg", "heading",
            "trk", "track", "course",
            "ias", "tas", "gndspd", "gs", "groundspeed", "gspd",
            "vspd", "verticalspeed", "vertical_speed", "vs",
            "altb", "altmsl", "altgps", "altitude", "baroalt", "alt"
        )
        var s = 0
        for (h in normHeaders) if (h in want) s++
        return s
    }

    // ---------- Column mapping ----------

    private data class ColIndex(val byField: Map<Field, Int>)

    private enum class Field {
        DATE, TIME, UTC_OFFSET, UTC_TIME, TIMESTAMP,
        LAT, LON,
        ALT_MSL, ALT_GPS, ALT_BARO, ALT_GENERIC,
        GS, IAS, TAS,
        VS,
        HEADING, TRACK,
        PITCH, ROLL
    }

    private fun buildColumnIndex(headerCells: List<String>): ColIndex {
        val map = mutableMapOf<Field, Int>()

        headerCells.forEachIndexed { idx, raw ->
            val h = normalizeHeader(raw)

            // čas
            when (h) {
                "lcldate", "date", "localdate" -> map.putIfAbsent(Field.DATE, idx)
                "lcltime", "time", "localtime" -> map.putIfAbsent(Field.TIME, idx)
                "utcofst", "utcoffset", "utc_offset", "utcplus", "utcoff" -> map.putIfAbsent(Field.UTC_OFFSET, idx)
                "utc", "utctime" -> map.putIfAbsent(Field.UTC_TIME, idx)
                "timestamp", "timeutc", "epochtime", "unix" -> map.putIfAbsent(Field.TIMESTAMP, idx)
            }

            // poloha
            when (h) {
                "latitude", "lat" -> map.putIfAbsent(Field.LAT, idx)
                "longitude", "lon", "long" -> map.putIfAbsent(Field.LON, idx)
            }

            // altitude
            when (h) {
                "altmsl", "mslalt", "alt_msl" -> map.putIfAbsent(Field.ALT_MSL, idx)
                "altgps", "gpsalt", "alt_gps" -> map.putIfAbsent(Field.ALT_GPS, idx)
                "altb", "baroalt", "altbaro", "alt_baro", "altitude_baro" -> map.putIfAbsent(Field.ALT_BARO, idx)
                "altitude", "alt" -> map.putIfAbsent(Field.ALT_GENERIC, idx)
            }

            // speeds
            when (h) {
                "gndspd", "groundspeed", "gs", "gspd" -> map.putIfAbsent(Field.GS, idx)
                "ias" -> map.putIfAbsent(Field.IAS, idx)
                "tas" -> map.putIfAbsent(Field.TAS, idx)
            }

            // vertical speed
            when (h) {
                "vspd", "verticalspeed", "vertical_speed", "vs" -> map.putIfAbsent(Field.VS, idx)
            }

            // attitude / direction
            when (h) {
                "hdg", "heading" -> map.putIfAbsent(Field.HEADING, idx)
                "trk", "track", "course" -> map.putIfAbsent(Field.TRACK, idx)
                "pitch" -> map.putIfAbsent(Field.PITCH, idx)
                "roll" -> map.putIfAbsent(Field.ROLL, idx)
            }
        }

        Log.i(TAG, "Mapped columns: $map")
        return ColIndex(map.toMap())
    }

    private fun normalizeHeader(s: String): String {
        return s.trim()
            .lowercase()
            .replace("\uFEFF", "")
            .replace("°", "")
            .replace("(deg)", "")
            .replace("(degrees)", "")
            .replace("(kt)", "")
            .replace("(kts)", "")
            .replace("(knots)", "")
            .replace("(ft)", "")
            .replace("(m)", "")
            .replace("(ft/min)", "")
            .replace("(fpm)", "")
            .replace("[^a-z0-9_]".toRegex(), "")
            .replace("__", "_")
    }

    // ---------- Row parsing ----------

    private fun parseRow(cells: List<String>, col: ColIndex, sourceLine: Int): FlightPointRaw? {
        fun get(field: Field): String? {
            val i = col.byField[field] ?: return null
            return if (i in cells.indices) cells[i].trim() else null
        }

        val lat = parseDouble(get(Field.LAT))
        val lon = parseDouble(get(Field.LON))

        val timeMillis = parseTimeMillis(
            dateStr = get(Field.DATE),
            timeStr = get(Field.TIME),
            utcOffsetStr = get(Field.UTC_OFFSET),
            utcTimeStr = get(Field.UTC_TIME),
            timestampStr = get(Field.TIMESTAMP)
        )

        val alt = firstNonNull(
            parseAltitudeToMeters(get(Field.ALT_BARO)),
            parseAltitudeToMeters(get(Field.ALT_MSL)),
            parseAltitudeToMeters(get(Field.ALT_GPS)),
            parseAltitudeToMeters(get(Field.ALT_GENERIC))
        )

        val gs = parseSpeedToMps(get(Field.GS))
        val ias = parseSpeedToMps(get(Field.IAS))
        val tas = parseSpeedToMps(get(Field.TAS))
        val vs = parseVerticalSpeedToMps(get(Field.VS))

        val heading = normalizeAngle(parseDouble(get(Field.HEADING)))
        val track = normalizeAngle(parseDouble(get(Field.TRACK)))

        val pitch = sanitizeAngle(parseDouble(get(Field.PITCH)), MAX_ABS_PITCH_DEG)
        val roll = sanitizeAngle(parseDouble(get(Field.ROLL)), MAX_ABS_ROLL_DEG)

        val gsSafe = if (gs != null && gsToKt(gs) > MAX_GS_KT) null else gs
        val vsSafe = if (vs != null && abs(msToFpm(vs)) > MAX_ABS_VS_FTPM) null else vs

        if (lat == null || lon == null) return null

        return FlightPointRaw(
            timeMillis = timeMillis,
            lat = lat,
            lon = lon,
            altM = alt,
            gsMps = gsSafe,
            vsMps = vsSafe,
            headingDeg = heading,
            trackDeg = track,
            pitchDeg = pitch,
            rollDeg = roll,
            iasMps = ias,
            tasMps = tas,
            sourceLine = sourceLine
        )
    }

    // ---------- Time parsing ----------

    private fun parseTimeMillis(
        dateStr: String?,
        timeStr: String?,
        utcOffsetStr: String?,
        utcTimeStr: String?,
        timestampStr: String?
    ): Long? {
        // 1) epoch/timestamp
        parseDouble(timestampStr)?.let { ts ->
            val asLong = ts.toLong()
            return when {
                asLong > 1_000_000_000_000L -> asLong
                asLong > 1_000_000_000L -> asLong * 1000L
                else -> null
            }
        }

        // 2) UTC time priamo (ak je)
        if (!utcTimeStr.isNullOrBlank()) {
            val s = utcTimeStr.trim()
            try {
                return Instant.parse(
                    if (s.endsWith("z", true)) s else s.replace(" ", "T") + "Z"
                ).toEpochMilli()
            } catch (_: Exception) { }
        }

        // 3) Local Date + Local Time + UTC offset
        if (!dateStr.isNullOrBlank() && !timeStr.isNullOrBlank()) {
            val date = parseLocalDate(dateStr.trim()) ?: return null
            val time = parseLocalTime(timeStr.trim()) ?: return null
            val ldt = LocalDateTime.of(date, time)

            val offset = parseUtcOffset(utcOffsetStr)
            return if (offset != null) {
                ldt.toInstant(offset).toEpochMilli()
            } else {
                // fallback: UTC
                ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }

        return null
    }

    private fun parseLocalDate(s: String): LocalDate? {
        val candidates = listOf(
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE
        )
        for (f in candidates) {
            try { return LocalDate.parse(s, f) } catch (_: DateTimeParseException) {}
        }
        return null
    }

    private fun parseLocalTime(s: String): LocalTime? {
        val candidates = listOf(
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm:ss.S"),
            DateTimeFormatter.ofPattern("H:mm:ss.SS"),
            DateTimeFormatter.ofPattern("H:mm:ss.SSS"),
            DateTimeFormatter.ISO_LOCAL_TIME
        )
        for (f in candidates) {
            try { return LocalTime.parse(s, f) } catch (_: DateTimeParseException) {}
        }
        return null
    }

    private fun parseUtcOffset(s: String?): ZoneOffset? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()

        // "-1.00" alebo "+2.00" (hodiny)
        parseDouble(t)?.let { hours ->
            val totalSeconds = (hours * 3600.0).toInt()
            return ZoneOffset.ofTotalSeconds(totalSeconds)
        }

        // "+01:00"
        return try { ZoneOffset.of(t) } catch (_: Exception) { null }
    }

    // ---------- Unit parsing helpers ----------

    private fun parseAltitudeToMeters(s: String?): Double? {
        val v = parseDouble(s) ?: return null
        return ftToM(v)   // Garmin avionics: altitude vždy interpretovaná ako ft
    }

    private fun parseSpeedToMps(s: String?): Double? {
        val v = parseDouble(s) ?: return null
        return ktToMps(v)
    }

    private fun parseVerticalSpeedToMps(s: String?): Double? {
        val v = parseDouble(s) ?: return null
        return fpmToMps(v)
    }

    private fun ftToM(ft: Double) = ft * 0.3048
    private fun ktToMps(kt: Double) = kt * 0.514444444444
    private fun fpmToMps(fpm: Double) = fpm * 0.00508

    private fun gsToKt(mps: Double) = mps / 0.514444444444
    private fun msToFpm(mps: Double) = mps / 0.00508

    private fun sanitizeAngle(v: Double?, maxAbs: Double): Double? {
        if (v == null) return null
        return if (abs(v) <= maxAbs) v else null
    }

    private fun normalizeAngle(v: Double?): Double? {
        if (v == null) return null
        var a = v % 360.0
        if (a < 0) a += 360.0
        return a
    }

    private fun firstNonNull(vararg v: Double?): Double? {
        for (x in v) if (x != null) return x
        return null
    }

    // ---------- CSV splitting (delimiter-aware) ----------

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                delimiter -> {
                    if (inQuotes) sb.append(c)
                    else {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseDouble(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
            .replace("\uFEFF", "")
            .replace(" ", "")
            .replace(",", ".")
        return t.toDoubleOrNull()
    }

    // ---------- RAW -> FlightPoint (unitný výstup ako ostatné parsery) ----------

    fun parseToFlightPoints(
        input: InputStream,
        preferIasOverGs: Boolean = true,
        fallbackDtSec: Double = 1.0,
        source: LogType = LogType.GARMIN_AVIONICS
    ): List<FlightPoint> {
        val rawPoints = parse(input)
        if (rawPoints.isEmpty()) return emptyList()

        val baseMs: Long? = rawPoints.firstNotNullOfOrNull { it.timeMillis }

        var lastTSec = 0.0
        var lastAbsSec: Double? = null

        return rawPoints.mapIndexedNotNull { idx, p ->
            val lat = p.lat ?: return@mapIndexedNotNull null
            val lon = p.lon ?: return@mapIndexedNotNull null

            val absSec: Double? = p.timeMillis?.let { it / 1000.0 }

            val tSec: Double
            val dtSec: Double

            if (baseMs != null && absSec != null) {
                val baseSec = baseMs / 1000.0
                tSec = absSec - baseSec
                dtSec = if (lastAbsSec != null) max(0.0, absSec - lastAbsSec!!) else 0.0
                lastAbsSec = absSec
                lastTSec = tSec
            } else {
                dtSec = if (idx == 0) 0.0 else fallbackDtSec
                tSec = if (idx == 0) 0.0 else (lastTSec + dtSec)
                lastTSec = tSec
            }

            val altM = p.altM ?: 0.0
            val speedMps = if (preferIasOverGs) (p.iasMps ?: p.gsMps) else (p.gsMps ?: p.iasMps)

            val heading = p.headingDeg
            val yaw = heading ?: p.trackDeg

            FlightPoint(
                tSec = tSec,
                dtSec = dtSec,
                latitude = lat,
                longitude = lon,
                altitudeM = altM,
                speedMps = speedMps,
                vsMps = p.vsMps,
                pitchDeg = p.pitchDeg,
                rollDeg = p.rollDeg,
                yawDeg = yaw,
                headingDeg = heading,
                source = source
            )
        }
    }
}