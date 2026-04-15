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
 * Parser pre avionické CSV záznamy z Garmin G1000 / G3X Touch a Dynon SkyView.
 *
 * Oba systémy exportujú CSV s hlavičkovým riadkom, ale líšia sa konvenciou názvov stĺpcov:
 *   - Garmin používa krátke identifikátory: IAS, VSpd, HDG, TRK, AltB, GndSpd, ...
 *   - Dynon SkyView (Revision AT) používa čitateľné názvy s jednotkami v zátvorkách:
 *     "Indicated Airspeed (knots)", "Vert Speed (ft./min)", "Magnetic Heading (deg)", ...
 * Parser detekuje formát automaticky cez skórovanie hlavičky a normalizáciu názvov.
 *
 * Štruktúra Garmin G1000 CSV (overená z letových záznamov):
 *   Riadok 1:  airplane info (registrácia, model, sériové číslo — preskočíme)
 *   Riadok 2:  hlavička stĺpcov:
 *              Lcl Date, Lcl Time, UTCOfst, AtvWpt, Latitude, Longitude,
 *              AltB, BaroA, AltMSL, OAT, IAS, GndSpd, VSpd, Pitch, Roll,
 *              LatAc, NormAc, HDG, TRK, ..., AltGPS, TAS, ..., VSpdG, ...
 *   Riadok 3:  jednotky (ft, kt, fpm, deg, ...) — explicitne preskočíme
 *   Riadok 4+: dáta, vzorkovanie 1 Hz
 *
 * Štruktúra Dynon SkyView CSV (podľa SkyView System Installation Guide, Revision AT):
 *   Riadok 1:  hlavička stĺpcov — viac ako 100 polí vrátane EMS vstupov:
 *              System Time, Pitch (deg), Roll (deg), Magnetic Heading (deg),
 *              Indicated Airspeed (knots), Pressure Altitude (ft.),
 *              Vert Speed (ft./min), GPS Latitude (deg), GPS Longitude (deg),
 *              GPS Altitude (ft.), GPS Speed (knots), GPS Track (deg), ...
 *   Riadok 2+: dáta, vzorkovanie konfigurovateľné (1–16 Hz)
 *   Poznámka:  SkyView exportuje iba čas (System Time), nie dátum — absolútne
 *              timestampy nie sú k dispozícii, časová os sa rekonštruuje z dt.
 *
 * Mapovanie kľúčových stĺpcov (oba formáty):
 *   HDG / Magnetic Heading    → Field.HEADING — magnetický smer nosa z AHRS
 *   TRK / GPS Track           → Field.TRACK   — smer pohybu nad zemou
 *   IAS / Indicated Airspeed  → Field.IAS     — rýchlosť pre HUD
 *   TAS / True Airspeed       → Field.TAS
 *   GndSpd / GPS Speed        → Field.GS
 *   VSpd / Vert Speed         → Field.VS      — barometrická (preferovaná)
 *   VSpdG                     → Field.VS      — GPS fallback (len G1000)
 *   AltB / Pressure Altitude  → Field.ALT_BARO — prioritná pre zobrazenie
 *   AltMSL                    → Field.ALT_MSL  — fallback
 *   AltGPS / GPS Altitude     → Field.ALT_GPS  — fallback
 *
 * Stĺpce ktoré parser zámerne ignoruje:
 *   CRS  — HSI selected course (≠ GPS track, je to zvolený kurz pre NAV)
 *   MagVar — magnetická variácia (nepotrebná pre vizualizáciu)
 *   WndSpd/WndDr — vietor (nepotrebný pre HUD)
 *   BaroA — nastavenie výškomera (nepotrebné)
 *   LatAc/NormAc — G-sily (nepotrebné)
 *   E1 * — motorové parametre (mimo rozsahu appky)
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

    // -----------------------------------------------------------------------
    // Verejné API
    // -----------------------------------------------------------------------

    fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use {
            parseToFlightPoints(
                input            = it,
                preferIasOverGs  = true,
                fallbackDtSec    = 1.0,
                source           = LogType.GARMIN_AVIONICS
            )
        }
    }

    /** RAW parse — ponechané pre debug/analýzu */
    fun parse(input: InputStream): List<FlightPointRaw> {
        BufferedReader(InputStreamReader(input)).use { br ->
            val allLines = mutableListOf<String>()
            var line: String?
            while (true) { line = br.readLine() ?: break; allLines.add(line) }
            if (allLines.isEmpty()) return emptyList()

            val delimiter              = detectDelimiter(allLines)
            val (headerIndex, headerCells) = findHeader(allLines, delimiter)

            if (headerIndex < 0) {
                Log.w(TAG, "Header not found.")
                return emptyList()
            }

            // G1000 má units riadok hneď za hlavičkou — zistíme ho a preskočíme
            val unitsRowIndex = detectUnitsRow(allLines, headerIndex, headerCells, delimiter)

            val columnIndex = buildColumnIndex(headerCells)
            val out         = ArrayList<FlightPointRaw>(allLines.size.coerceAtMost(50_000))

            for (i in headerIndex + 1 until allLines.size) {
                if (i == unitsRowIndex) continue          // ← preskoč units riadok
                val raw = allLines[i].trim()
                if (raw.isEmpty()) continue
                if (raw.startsWith("sep=", ignoreCase = true)) continue

                val cells = splitCsvLine(raw, delimiter)
                if (cells.isEmpty()) continue

                val p = parseRow(cells, columnIndex, i + 1) ?: continue
                out.add(p)
            }

            return out
        }
    }

    // -----------------------------------------------------------------------
    // Delimiter + header detection
    // -----------------------------------------------------------------------

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
     * Detekuje G1000/G3X units riadok (hneď za hlavičkou).
     * Units riadok obsahuje jednotky ako "ft", "kt", "fpm", "deg" namiesto čísel.
     * Ak nasledujúci riadok za hlavičkou nie je parsovateľný ako dáta (lat/lon sú
     * textové jednotky), považujeme ho za units riadok a vrátime jeho index.
     * Inak vrátime -1.
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

        val cells = splitCsvLine(row, delimiter)

        // Ak bunky na pozíciách lat/lon nie sú čísla, je to units riadok
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
        } else {
            -1
        }
    }

    private fun headerScore(normHeaders: List<String>): Int {
        val want = setOf(
            "latitude", "lat", "gpslat", "longitude", "lon", "long", "gpslon",
            "pitch", "roll",
            "hdg", "heading", "magneticheading",
            "trk", "track", "course", "gpstrack",
            "ias", "tas", "airspeed", "indicatedairspeed", "trueairspeed",
            "gndspd", "gs", "groundspeed", "gspd", "gpsspeed",
            "vspd", "verticalspeed", "vertical_speed", "vs", "vspdg", "vertspeed",
            "altb", "altmsl", "altgps", "altitude", "baroalt", "alt",
            "pressurealtitude", "gpsaltitude",
            "lcltime", "time", "systemtime"
        )
        return normHeaders.count { it in want }
    }

    // -----------------------------------------------------------------------
    // Column mapping
    // -----------------------------------------------------------------------

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

            when (h) {
                // čas
                "lcldate", "date", "localdate"                          -> map.putIfAbsent(Field.DATE, idx)
                // Dynon SkyView: "System Time" → "systemtime" (obsahuje čas bez dátumu)
                "lcltime", "time", "localtime", "systemtime"            -> map.putIfAbsent(Field.TIME, idx)
                "utcofst", "utcoffset", "utc_offset", "utcplus", "utcoff" -> map.putIfAbsent(Field.UTC_OFFSET, idx)
                "utc", "utctime"                                        -> map.putIfAbsent(Field.UTC_TIME, idx)
                "timestamp", "timeutc", "epochtime", "unix"             -> map.putIfAbsent(Field.TIMESTAMP, idx)

                // poloha
                // G1000/G3X: "Latitude", "Longitude"
                // Dynon SkyView: "GPS Latitude (deg)" → "gpslat[itude]", "GPS Longitude (deg)" → "gpslon[gitude]"
                "latitude", "lat", "gpslatitude", "gpslat" -> map.putIfAbsent(Field.LAT, idx)
                "longitude", "lon", "long",
                "gpslongitude", "gpslon"            -> map.putIfAbsent(Field.LON, idx)

                // výška — priorita: barometrická > MSL > GPS > generická
                // Dynon SkyView: "Pressure Altitude (ft.)" → "pressurealtitude"
                // Dynon SkyView: "GPS Altitude (ft.)" → "gpsaltitude"
                "altb", "baroalt", "altbaro", "alt_baro", "altitude_baro",
                "pressurealtitude"                  -> map.putIfAbsent(Field.ALT_BARO, idx)
                "altmsl", "mslalt", "alt_msl"       -> map.putIfAbsent(Field.ALT_MSL, idx)
                "altgps", "gpsalt", "alt_gps",
                "gpsaltitude"                       -> map.putIfAbsent(Field.ALT_GPS, idx)
                "altitude", "alt"                   -> map.putIfAbsent(Field.ALT_GENERIC, idx)

                // rýchlosti
                // G1000/G3X: "GndSpd", "IAS", "TAS"
                // Dynon SkyView: "GPS Speed (knots)" → "gpsspeed", "Indicated Airspeed (knots)" → "indicatedairspeed"
                //                "True Airspeed (knots)" → "trueairspeed"
                "gndspd", "groundspeed", "gs", "gspd",
                "gpsspeed", "gpsgndspd"             -> map.putIfAbsent(Field.GS, idx)
                "ias", "airspeed", "indicatedairspeed" -> map.putIfAbsent(Field.IAS, idx)
                "tas", "trueairspeed"               -> map.putIfAbsent(Field.TAS, idx)

                // vertikálna rýchlosť
                // G1000/G3X: "VSpd" (barometrická, preferovaná), "VSpdG" (GPS fallback)
                // Dynon SkyView: "Vert Speed (ft./min)" → "vertspeed"
                //                "Vertical Speed (ft./min)" → "verticalspeed"
                "vspd", "verticalspeed", "vertical_speed", "vs",
                "vspdg", "vertspeed"                -> map.putIfAbsent(Field.VS, idx)

                // smer
                // G1000/G3X: "HDG" (magnetický heading), "TRK" (GPS track)
                // Dynon SkyView: "Magnetic Heading (deg)" → "magneticheading"
                //                "GPS Track (deg)" → "gpstrack"
                // NE mapujeme "crs" (= HSI selected course, nie smer pohybu)
                "hdg", "heading", "magneticheading" -> map.putIfAbsent(Field.HEADING, idx)
                "trk", "track", "course",
                "gpstrack", "gpscourse"             -> map.putIfAbsent(Field.TRACK, idx)

                // postoj
                "pitch"                             -> map.putIfAbsent(Field.PITCH, idx)
                "roll"                              -> map.putIfAbsent(Field.ROLL, idx)
            }
        }

        Log.i(TAG, "Mapped columns: $map")
        return ColIndex(map.toMap())
    }

    private fun normalizeHeader(s: String): String =
        s.trim()
            .lowercase()
            .replace("\uFEFF", "")
            .replace("°", "")
            .replace("(deg)", "").replace("(degrees)", "")
            .replace("(kt)", "").replace("(kts)", "").replace("(knots)", "")
            .replace("(ft.)", "").replace("(ft)", "").replace("(m)", "")
            // Dynon SkyView používa "(ft./min)" so bodkou — musí byť pred "(ft/min)"
            .replace("(ft./min)", "").replace("(ft/min)", "").replace("(fpm)", "")
            .replace("[^a-z0-9_]".toRegex(), "")
            .replace("__", "_")

    // -----------------------------------------------------------------------
    // Row parsing
    // -----------------------------------------------------------------------

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

        val gs      = parseSpeedToMps(get(Field.GS))
        val ias     = parseSpeedToMps(get(Field.IAS))
        val tas     = parseSpeedToMps(get(Field.TAS))
        val vs      = parseVerticalSpeedToMps(get(Field.VS))

        val heading = normalizeAngle(parseDouble(get(Field.HEADING)))
        val track   = normalizeAngle(parseDouble(get(Field.TRACK)))
        val pitch   = sanitizeAngle(parseDouble(get(Field.PITCH)), MAX_ABS_PITCH_DEG)
        val roll    = sanitizeAngle(parseDouble(get(Field.ROLL)),  MAX_ABS_ROLL_DEG)

        val gsSafe = if (gs != null && gsToKt(gs) > MAX_GS_KT) null else gs
        val vsSafe = if (vs != null && abs(msToFpm(vs)) > MAX_ABS_VS_FTPM) null else vs

        return FlightPointRaw(
            timeMillis  = timeMillis,
            lat         = lat,
            lon         = lon,
            altM        = alt,
            gsMps       = gsSafe,
            vsMps       = vsSafe,
            headingDeg  = heading,
            trackDeg    = track,
            pitchDeg    = pitch,
            rollDeg     = roll,
            iasMps      = ias,
            tasMps      = tas,
            sourceLine  = sourceLine
        )
    }

    // -----------------------------------------------------------------------
    // Time parsing
    // -----------------------------------------------------------------------

    private fun parseTimeMillis(
        dateStr: String?, timeStr: String?,
        utcOffsetStr: String?, utcTimeStr: String?, timestampStr: String?
    ): Long? {
        // 1) epoch timestamp
        parseDouble(timestampStr)?.let { ts ->
            val l = ts.toLong()
            return when {
                l > 1_000_000_000_000L -> l
                l > 1_000_000_000L     -> l * 1000L
                else                   -> null
            }
        }

        // 2) ISO UTC timestamp
        if (!utcTimeStr.isNullOrBlank()) {
            val s = utcTimeStr.trim()
            try {
                return Instant.parse(if (s.endsWith("z", true)) s else s.replace(" ", "T") + "Z")
                    .toEpochMilli()
            } catch (_: Exception) { }
        }

        // 3) G1000 štýl: Lcl Date + Lcl Time + UTCOfst
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

    // -----------------------------------------------------------------------
    // Unit conversions
    // -----------------------------------------------------------------------

    private fun parseAltitudeToMeters(s: String?) = parseDouble(s)?.let { it * 0.3048 }
    private fun parseSpeedToMps(s: String?)        = parseDouble(s)?.let { it * 0.514444 }
    private fun parseVerticalSpeedToMps(s: String?)= parseDouble(s)?.let { it * 0.00508 }

    private fun gsToKt(mps: Double)  = mps / 0.514444
    private fun msToFpm(mps: Double) = mps / 0.00508

    private fun sanitizeAngle(v: Double?, maxAbs: Double) =
        v?.takeIf { abs(it) <= maxAbs }

    private fun normalizeAngle(v: Double?): Double? {
        if (v == null) return null
        var a = v % 360.0
        if (a < 0) a += 360.0
        return a
    }

    private fun firstNonNull(vararg v: Double?) = v.firstOrNull { it != null }

    // -----------------------------------------------------------------------
    // CSV split
    // -----------------------------------------------------------------------

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb  = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"'       -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { out.add(sb.toString()); sb.setLength(0) }
                else           -> sb.append(c)
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

    // -----------------------------------------------------------------------
    // RAW → FlightPoint
    // -----------------------------------------------------------------------

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
                dtSec     = if (idx == 0) 0.0 else fallbackDtSec
                tSec      = if (idx == 0) 0.0 else lastTSec + dtSec
                lastTSec  = tSec
            }

            // HDG = magnetický heading z AHRS (Field.HEADING)
            // TRK = GPS track (Field.TRACK) — použijeme ako yawDeg fallback
            val heading  = p.headingDeg
            val yaw      = heading ?: p.trackDeg

            // Rýchlosť: preferuj IAS (to čo pilot vidí) pred GndSpd
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