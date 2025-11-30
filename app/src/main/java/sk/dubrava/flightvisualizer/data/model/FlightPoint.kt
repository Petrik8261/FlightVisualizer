package sk.dubrava.flightvisualizer.data.model

data class FlightPoint(
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val temperature: Double,
    val pressure: Double,
    val altitude: Double,
    val x: Double,
    val y: Double,
    val z: Double
)


