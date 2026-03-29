package sk.dubrava.flightvisualizer.data.model

data class FlightPoint(
    val tSec: Double,
    val dtSec: Double,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,

    val speedMps: Double?,
    val vsMps: Double?,

    val pitchDeg: Double?,
    val rollDeg: Double?,
    val yawDeg: Double?,
    val headingDeg: Double?,

    val source: LogType
)

enum class LogType {
    MSFS,
    DRONE,
    KML,
    KML_TRACK,
    GPX,
    ARDUINO_TXT,
    GENERIC,
    GARMIN_AVIONICS
}