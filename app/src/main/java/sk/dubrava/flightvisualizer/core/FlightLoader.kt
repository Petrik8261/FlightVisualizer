package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import sk.dubrava.flightvisualizer.data.normalize.DataNormalizer
import sk.dubrava.flightvisualizer.data.parser.ArduinoTxtParser
import sk.dubrava.flightvisualizer.data.parser.DroneCsvParser
import sk.dubrava.flightvisualizer.data.parser.GarminAvionicsCsvParser
import sk.dubrava.flightvisualizer.data.parser.KmlFlightParser
import sk.dubrava.flightvisualizer.data.parser.MsfsCsvParser
import sk.dubrava.flightvisualizer.data.parser.TxtCsvFlightParser
import java.util.Locale

class FlightLoader(
    private val contentResolver: ContentResolver
) {
    companion object { private const val TAG = "FlightLoader" }

    private val kmlParser             = KmlFlightParser(contentResolver)
    private val txtCsvParser          = TxtCsvFlightParser(contentResolver)
    private val arduinoTxtParser      = ArduinoTxtParser(contentResolver)
    private val msfsCsvParser         = MsfsCsvParser(contentResolver)
    private val droneCsvParser        = DroneCsvParser(contentResolver)
    private val garminAvionicsCsvParser = GarminAvionicsCsvParser(contentResolver)

    private enum class CsvType { GARMIN_AVIONICS, MSFS, DJI, AIRDATA, UNKNOWN }

    private fun detectCsvType(uri: Uri): CsvType {
        val header = readHeaderLowerScan(uri, maxLines = 80)

        return when {
            header.contains("pitch") &&
                    header.contains("roll") &&
                    (header.contains("latitude") || header.contains("lat")) &&
                    (header.contains("longitude") || header.contains("lon") || header.contains("long")) ->
                CsvType.GARMIN_AVIONICS

            header.contains("timestamp") &&
                    header.contains("utc") &&
                    header.contains("heading") ->
                CsvType.MSFS

            (header.any { it.startsWith("osd.") } || header.contains("osd.flytime [s]")) &&
                    header.contains("osd.latitude") &&
                    header.contains("osd.longitude") ->
                CsvType.DJI

            header.contains("time(millisecond)") &&
                    header.contains("latitude") &&
                    header.contains("longitude") ->
                CsvType.AIRDATA

            else -> CsvType.UNKNOWN
        }
    }

    /**
     * Skenuje prvých maxLines riadkov a vyberie ten, ktorý najlepšie zodpovedá
     * telemetrickej hlavičke (score podľa prítomnosti kľúčových názvov stĺpcov).
     * Podporuje "sep=;" prefix a automatickú detekciu oddeľovača.
     */
    private fun readHeaderLowerScan(uri: Uri, maxLines: Int = 80): Set<String> {
        val input = contentResolver.openInputStream(uri) ?: return emptySet()
        val reader = input.bufferedReader()

        reader.use { br ->
            var delimiter: Char? = null
            var bestLine: String? = null
            var bestScore = -1

            var linesRead = 0
            while (linesRead < maxLines) {
                val line = br.readLine() ?: break
                val trim = line.trim()
                if (trim.isEmpty()) { linesRead++; continue }

                if (trim.startsWith("sep=", ignoreCase = true)) {
                    delimiter = trim.substringAfter("sep=").firstOrNull()
                    linesRead++
                    continue
                }

                val delim = delimiter ?: sniffDelimiter(line)
                val cols = line.split(delim).map { it.trim() }.filter { it.isNotEmpty() }
                if (cols.size < 4) { linesRead++; continue }

                val norm = cols.map { it.lowercase(Locale.ROOT) }.toSet()
                val score = headerScore(norm)

                if (score > bestScore) {
                    bestScore = score
                    bestLine = line
                    delimiter = delim
                }

                if (bestScore >= 6) break
                linesRead++
            }

            if (bestLine == null || delimiter == null) return emptySet()

            return bestLine
                .split(delimiter)
                .map { it.trim().lowercase(Locale.ROOT) }
                .toSet()
        }
    }

    private fun sniffDelimiter(line: String): Char {
        val candidates = listOf(',', ';', '\t', '|')
        return candidates.maxBy { c -> line.count { it == c } }
    }

    private fun headerScore(header: Set<String>): Int {
        val wanted = listOf(
            "latitude", "lat", "longitude", "lon", "long",
            "pitch", "roll", "heading", "hdg", "track", "trk",
            "ias", "tas", "gndspd", "gs", "groundspeed",
            "vspd", "vs", "verticalspeed",
            "altb", "altmsl", "altgps", "altitude", "alt"
        )
        var s = 0
        wanted.forEach { w -> if (header.contains(w)) s++ }
        return s
    }

    fun load(uri: Uri, mode: DerivedMode): List<FlightPoint> {
        val name = guessFileName(uri)?.lowercase(Locale.ROOT) ?: ""

        val parsed: List<FlightPoint> = when {
            name.endsWith(".kml") -> {
                Log.i(TAG, "Parser=KML")
                kmlParser.parse(uri)
            }
            name.endsWith(".txt") -> {
                Log.i(TAG, "Parser=ArduinoTXT")
                arduinoTxtParser.parse(uri)
            }
            name.endsWith(".csv") -> {
                when (detectCsvType(uri)) {
                    CsvType.GARMIN_AVIONICS -> {
                        Log.i(TAG, "Parser=Garmin Avionics CSV (G1000/G3X)")
                        garminAvionicsCsvParser.parse(uri)
                    }
                    CsvType.MSFS -> {
                        Log.i(TAG, "Parser=MSFS SkyDolly CSV")
                        msfsCsvParser.parse(uri)
                    }
                    CsvType.DJI -> {
                        Log.i(TAG, "Parser=DJI FlightRecord CSV")
                        droneCsvParser.parse(uri)
                    }
                    CsvType.AIRDATA -> {
                        Log.i(TAG, "Parser=AirData CSV")
                        droneCsvParser.parse(uri)
                    }
                    CsvType.UNKNOWN -> {
                        Log.i(TAG, "Parser=CSV fallback (TxtCsvFlightParser)")
                        txtCsvParser.parse(uri)
                    }
                }
            }
            else -> {
                Log.i(TAG, "Parser=fallback (TxtCsvFlightParser)")
                txtCsvParser.parse(uri)
            }
        }

        if (parsed.size < 2) return parsed

        if (mode == DerivedMode.RAW) {
            val rawOut = when (parsed.first().source) {
                LogType.KML -> {
                    // V RAW móde nullujeme veličiny dopočítané v parseri (tilt→pitch, dist/dt→speed)
                    // rollDeg ponechávame — je priamo z Camera elementu
                    parsed.map {
                        it.copy(speedMps = null, vsMps = null, pitchDeg = null)
                    }
                }
                LogType.ARDUINO_TXT -> {
                    // pitch/roll sú z akcelerometra, VS a speed sú dopočítané — v RAW nullujeme všetko
                    parsed.map {
                        it.copy(speedMps = null, vsMps = null, pitchDeg = null, rollDeg = null)
                    }
                }
                else -> parsed
            }
            Log.i(TAG, "Loaded points=${rawOut.size} (RAW)")
            return rawOut
        }

        val assisted = DataNormalizer.normalize(parsed)
        Log.i(TAG, "Loaded points=${assisted.size} (ASSISTED)")
        return assisted
    }

    private fun guessFileName(uri: Uri): String? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}