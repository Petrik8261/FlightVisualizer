package sk.dubrava.flightvisualizer.data.model

data class FlightPoint(
    val tSec: Double,
    val dtSec: Double,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,

    val speedMps: Double?,     // nullable ak nie je
    val vsMps: Double?,        // nullable ak nie je

    val pitchDeg: Double?,     // deg
    val rollDeg: Double?,      // deg
    val yawDeg: Double?,       // deg (heading/yaw)
    val headingDeg: Double?,   // voliteľne ak chceš odlíšiť heading vs yaw

    val source: LogType
)

enum class LogType {
    MSFS,
    DRONE,
    KML,
    ARDUINO_TXT,
    GENERIC,
    GARMIN_AVIONICS
}










