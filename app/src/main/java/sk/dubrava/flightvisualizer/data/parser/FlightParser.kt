package sk.dubrava.flightvisualizer.data.parser

import android.net.Uri
import sk.dubrava.flightvisualizer.data.model.FlightPoint

interface FlightParser {
    fun parse(uri: Uri): List<FlightPoint>
}
