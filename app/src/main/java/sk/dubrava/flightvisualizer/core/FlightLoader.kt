package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import sk.dubrava.flightvisualizer.data.normalize.DataNormalizer
import sk.dubrava.flightvisualizer.data.parser.ArduinoTxtParser
import sk.dubrava.flightvisualizer.data.parser.DroneCsvParser
import sk.dubrava.flightvisualizer.data.parser.KmlFlightParser
import sk.dubrava.flightvisualizer.data.parser.MsfsCsvParser
import sk.dubrava.flightvisualizer.data.parser.TxtCsvFlightParser
import sk.dubrava.flightvisualizer.data.parser.GarminAvionicsCsvParser // ✅ nový parser (nižšie)
import java.util.Locale

class FlightLoader(
    private val contentResolver: ContentResolver
) {
    companion object { private const val TAG = "FlightLoader" }

    private val kmlParser = KmlFlightParser(contentResolver)
    private val txtCsvParser = TxtCsvFlightParser(contentResolver)
    private val arduinoTxtParser = ArduinoTxtParser(contentResolver)
    private val msfsCsvParser = MsfsCsvParser(contentResolver)
    private val droneCsvParser = DroneCsvParser(contentResolver)

    private val garminAvionicsCsvParser = GarminAvionicsCsvParser(contentResolver) // ✅

    private enum class CsvType { GARMIN_AVIONICS, MSFS, DJI, AIRDATA, UNKNOWN }

    private fun detectCsvType(uri: Uri): CsvType {
        // Garmin avionics CSV môže mať prologue, hlavička nemusí byť 1. riadok
        val header = readHeaderLowerScan(uri, maxLines = 80)

        return when {
            // ✅ Garmin avionics (G1000/G3X): attitude + poloha
            header.contains("pitch") &&
                    header.contains("roll") &&
                    (header.contains("latitude") || header.contains("lat")) &&
                    (header.contains("longitude") || header.contains("lon") || header.contains("long")) ->
                CsvType.GARMIN_AVIONICS

            // MSFS SkyDolly (tvoja existujúca signatúra)
            header.contains("timestamp") &&
                    header.contains("utc") &&
                    header.contains("heading") ->
                CsvType.MSFS

            // DJI FlightRecord
            (header.any { it.startsWith("osd.") } || header.contains("osd.flytime [s]")) &&
                    header.contains("osd.latitude") &&
                    header.contains("osd.longitude") ->
                CsvType.DJI

            // AirData export
            header.contains("time(millisecond)") &&
                    header.contains("latitude") &&
                    header.contains("longitude") ->
                CsvType.AIRDATA

            else -> CsvType.UNKNOWN
        }
    }

    /**
     * Číta "hlavičku" robustne:
     * - podporí "sep=;"
     * - prejde prvých maxLines riadkov (Garmin avionics má často prologue)
     * - vyberie riadok, ktorý vyzerá najviac ako hlavička (veľa stĺpcov + kľúčové názvy)
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

                // "sep=;" handling (Excel)
                if (trim.startsWith("sep=", ignoreCase = true)) {
                    delimiter = trim.substringAfter("sep=").firstOrNull()
                    linesRead++
                    continue
                }

                // delimiter sniff: ak ešte nemáme, skús na tomto riadku
                val delim = delimiter ?: sniffDelimiter(line)

                // kandidát na hlavičku musí mať viac stĺpcov (komponenty avioniky bývajú bohaté)
                val cols = line.split(delim).map { it.trim() }.filter { it.isNotEmpty() }
                if (cols.size < 4) { linesRead++; continue }

                val norm = cols.map { it.lowercase(Locale.ROOT) }.toSet()
                val score = headerScore(norm)

                if (score > bestScore) {
                    bestScore = score
                    bestLine = line
                    delimiter = delim
                }

                // keď už máme silný kandidát, môžeme skončiť skôr
                if (bestScore >= 6) break

                linesRead++
            }

            if (bestLine == null || delimiter == null) return emptySet()

            return bestLine!!
                .split(delimiter!!)
                .map { it.trim().lowercase(Locale.ROOT) }
                .toSet()
        }
    }

    private fun sniffDelimiter(line: String): Char {
        val candidates = listOf(',', ';', '\t', '|')
        return candidates.maxBy { c -> line.count { it == c } }
    }

    private fun headerScore(header: Set<String>): Int {
        // scoring pre "vyzerá ako telemetrická hlavička"
        val wanted = listOf(
            "latitude", "lat",
            "longitude", "lon", "long",
            "pitch", "roll",
            "heading", "hdg",
            "track", "trk",
            "ias", "tas",
            "gndspd", "gs", "groundspeed",
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

        // RAW = len surové dáta + "hard policy" pre zdroje, kde nechceme ukazovať odvodené veci
        if (mode == DerivedMode.RAW) {
            val rawOut = when (parsed.first().source) {
                LogType.KML -> {
                    // RAW KML: pitch/VS/speed nepovažujeme za reálne (tilt + výpočty) => null
                    parsed.map {
                        it.copy(
                            speedMps = null,
                            vsMps = null,
                            pitchDeg = null
                            // rollDeg nechávame (je priamo v KML)
                            // yaw/heading nechávame tak ako parser dal (ak chceš striktne, tiež null)
                        )
                    }
                }

                LogType.ARDUINO_TXT -> {
                    // RAW Arduino TXT: pitch/roll (z accel) + VS (z alt) + speed (z GPS) sú odvodené => null
                    parsed.map {
                        it.copy(
                            speedMps = null,
                            vsMps = null,
                            pitchDeg = null,
                            rollDeg = null
                        )
                    }
                }

                else -> parsed
            }

            Log.i(TAG, "Loaded points=${rawOut.size} (RAW)")
            return rawOut
        }

        // ASSISTED = normalizácia + doplnenie estimated veličín
        val assisted = DataNormalizer.normalize(parsed)
        Log.i(TAG, "Loaded points=${assisted.size} (ASSISTED normalized)")
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