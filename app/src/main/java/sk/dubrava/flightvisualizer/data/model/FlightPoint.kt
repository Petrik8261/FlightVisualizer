package sk.dubrava.flightvisualizer.data.model

enum class TimeSource {
    UNKNOWN,
    REAL_TIMESTAMP,   // reálny čas z logu (Arduino/CSV)
    FIXED_RATE,       // napr. 10 Hz, 5 Hz...
    KML_TOUR_DURATION // gx:duration (čas animácie kamery)
}

data class FlightPoint(
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val heading: Double,
    val pitch: Double,
    val roll: Double,


    val dtSec: Double = Double.NaN,
    val timeSource: TimeSource = TimeSource.UNKNOWN,


    val speedKmh: Double? = null,
    val vsMps: Double? = null,
    val yawDeg: Double? = null
)







