package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import sk.dubrava.flightvisualizer.data.model.FlightPoint

class FlightHelper(
    private val contentResolver: ContentResolver
) {

    companion object {
    }

    private val loader = FlightLoader(contentResolver)

    fun loadFlight(uri: Uri, mode: DerivedMode): List<FlightPoint> {
        return loader.load(uri, mode)
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

}

