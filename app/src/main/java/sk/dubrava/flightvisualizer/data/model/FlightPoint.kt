package sk.dubrava.flightvisualizer.data.model

data class FlightPoint(
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,

    // Letové parametre z KML
    val heading: Double,     // smer letu v stupňoch
    val pitch: Double,       // tilt – stúpanie/klesanie
    val roll: Double         // náklon doľava/doprava
)



