package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import java.util.Locale

class FlightLoader(
    private val contentResolver: ContentResolver
) {
    companion object { private const val TAG = "FlightLoader" }

    private val kmlParser = KmlFlightParser(contentResolver)
    private val txtCsvParser = TxtCsvFlightParser(contentResolver)

    fun load(uri: Uri): List<FlightPoint> {
        val name = guessFileName(uri)?.lowercase(Locale.ROOT) ?: ""

        val result = try {
            when {
                name.endsWith(".kml") -> kmlParser.parse(uri)
                name.endsWith(".txt") || name.endsWith(".csv") -> txtCsvParser.parse(uri)
                else -> {
                    // fallback: skús KML, potom TXT/CSV
                    val kmlTry = kmlParser.parse(uri)
                    if (kmlTry.isNotEmpty()) kmlTry else txtCsvParser.parse(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load error", e)
            emptyList()
        }

        return result
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
