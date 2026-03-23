package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import sk.dubrava.flightvisualizer.data.model.FlightPoint

class FlightHelper(
    contentResolver: ContentResolver
) {

    companion object;

    private val loader = FlightLoader(contentResolver)

    fun loadFlight(uri: Uri, mode: DerivedMode): List<FlightPoint> {
        return loader.load(uri, mode)
    }

    fun buildRoute(points: List<FlightPoint>): List<LatLng> =
        points.map { LatLng(it.latitude, it.longitude) }

}

