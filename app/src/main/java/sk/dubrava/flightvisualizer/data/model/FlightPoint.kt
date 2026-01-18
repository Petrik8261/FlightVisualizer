package sk.dubrava.flightvisualizer.data.model

data class FlightPoint(
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val heading: Double,
    val pitch: Double,
    val roll: Double,

    val dtSec: Double = Double.NaN,
    val timeSource: TimeSource = TimeSource.REAL_TIMESTAMP,

    val speedKmh: Double? = null,
    val vsMps: Double? = null,
    val yawDeg: Double? = null
)








