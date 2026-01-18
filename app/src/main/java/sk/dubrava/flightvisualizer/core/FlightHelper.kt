package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import java.util.Locale

class FlightHelper(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "FlightHelper"
    }

    private val loader = FlightLoader(contentResolver)

    fun loadFlight(uri: Uri): List<FlightPoint> {
        return try {
            val points = loader.load(uri)
            if (points.size < 2) {
                Log.w(TAG, "loadFlight: not enough points (${points.size})")
            }
            points
        } catch (e: Exception) {
            Log.e(TAG, "loadFlight failed", e)
            emptyList()
        }
    }

    fun buildRoute(points: List<FlightPoint>): List<LatLng> =
        points.map { LatLng(it.latitude, it.longitude) }

    fun guessFileName(uri: Uri): String? {
        return try {
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
            uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    fun isSupported(uri: Uri): Boolean {
        val name = (guessFileName(uri) ?: "").lowercase(Locale.ROOT)
        return name.endsWith(".kml") || name.endsWith(".txt") || name.endsWith(".csv")
    }
}
