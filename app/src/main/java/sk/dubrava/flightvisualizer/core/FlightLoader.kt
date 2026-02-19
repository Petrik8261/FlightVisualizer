package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.normalize.DataNormalizer
import sk.dubrava.flightvisualizer.data.parser.DroneCsvParser
import sk.dubrava.flightvisualizer.data.parser.MsfsCsvParser
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

    private enum class CsvType { MSFS, DRONE, UNKNOWN }

    private fun detectCsvType(uri: Uri): CsvType {
        val header = readHeaderLower(uri)

        return when {
            header.contains("timestamp") &&
                    header.contains("utc") &&
                    header.contains("heading") -> CsvType.MSFS

            header.any { it.startsWith("osd.flytime") } &&
                    header.contains("osd.latitude") -> CsvType.DRONE

            else -> CsvType.UNKNOWN
        }
    }

    private fun readHeaderLower(uri: Uri): Set<String> {
        val input = contentResolver.openInputStream(uri) ?: return emptySet()
        val lines = input.bufferedReader().readLines()
        if (lines.isEmpty()) return emptySet()

        val headerLine =
            if (lines[0].startsWith("sep=", true) && lines.size > 1)
                lines[1]
            else
                lines[0]

        return headerLine
            .split(",")
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()
    }

    fun load(uri: Uri): List<FlightPoint> {

        val name = guessFileName(uri)?.lowercase(Locale.ROOT) ?: ""

        val parsed = when {
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
                    CsvType.MSFS -> {
                        Log.i(TAG, "Parser=MSFS SkyDolly CSV")
                        msfsCsvParser.parse(uri)
                    }
                    CsvType.DRONE -> {
                        Log.i(TAG, "Parser=DJI FlightRecord CSV")
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

        val out = DataNormalizer.normalize(parsed)
        Log.i(TAG, "Loaded points=${out.size} (normalized)")
        return out
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

