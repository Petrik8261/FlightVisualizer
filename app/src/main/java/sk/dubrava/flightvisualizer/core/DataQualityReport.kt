package sk.dubrava.flightvisualizer.core

data class DataQualityReport(
    val fileName: String,
    val detectedType: String,
    val pointsCount: Int,
    val durationSec: Double?,
    val dtAvgSec: Double?,
    val unitsAltitude: String?, // "ft", "m", null
    val flags: Flags,
    val warnings: List<String> = emptyList()
) {
    data class Flags(
        val hasLatLon: Boolean,
        val hasTime: Boolean,
        val hasAltitude: Boolean,
        val hasHeading: Boolean,
        val hasPitch: Boolean,
        val hasRoll: Boolean,
        val hasSpeed: Boolean,
        val hasVerticalSpeed: Boolean,

        // či vieme odvodiť
        val canEstimateSpeed: Boolean,
        val canEstimateVs: Boolean
    )

    enum class Availability { PRESENT, ESTIMABLE, MISSING }

    fun availabilitySpeed(): Availability = when {
        flags.hasSpeed -> Availability.PRESENT
        flags.canEstimateSpeed -> Availability.ESTIMABLE
        else -> Availability.MISSING
    }

    fun availabilityVs(): Availability = when {
        flags.hasVerticalSpeed -> Availability.PRESENT
        flags.canEstimateVs -> Availability.ESTIMABLE
        else -> Availability.MISSING
    }
}