package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.TimeSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

class TxtCsvFlightParser(
    private val contentResolver: ContentResolver
) {
    companion object { private const val TAG = "TxtCsvFlightParser" }

    fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()

        val lines = input.bufferedReader().useLines { seq ->
            seq.map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
        if (lines.size < 2) return emptyList()

        fun splitLineSmart(line: String): List<String> =
            when {
                line.contains("\t") -> line.split(Regex("\t+"))
                line.contains(";") -> line.split(";")
                line.contains(",") -> line.split(",")
                else -> line.split(Regex("\\s+"))
            }.map { it.trim() }

        fun toD(s: String?): Double? = s?.replace(",", ".")?.toDoubleOrNull()

        fun Double.isValidLat() = this.isFinite() && this in -90.0..90.0
        fun Double.isValidLon() = this.isFinite() && this in -180.0..180.0

        fun parseHmsToSec(s: String): Double? {
            val p = s.trim().split(":")
            if (p.size != 3) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            val sec = p[2].toIntOrNull() ?: return null
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        fun parseTimeToSec(s: String): Double? {
            val v = s.trim()
            return if (v.contains(":")) {
                parseHmsToSec(v)
            } else {
                v.replace(",", ".").toDoubleOrNull()?.let { msOrSec ->
                    if (msOrSec > 10_000.0) msOrSec / 1000.0 else msOrSec
                }
            }
        }

        val header = splitLineSmart(lines.first())
            .map { it.removePrefix("\uFEFF").lowercase(Locale.ROOT) }

        fun idx(vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k ->
                header.indexOfFirst { it == k }.takeIf { it >= 0 }
            }

        val iTime = idx("time", "time_ms", "timestamp", "t_s") ?: return emptyList()
        val iLat  = idx("latitude", "lat", "lat_deg") ?: return emptyList()
        val iLon  = idx("longitude", "lon", "lon_deg", "lng") ?: return emptyList()
        val iAlt  = idx("altitude", "altitude (m)", "alt_m", "alt", "height", "height(m)") ?: return emptyList()
        val iSpd = idx("speed_mps", "groundspeed_mps", "spd_mps", "speed", "vel_mps")
        val iVs  = idx("vspeed_mps", "verticalspeed_mps", "vs_mps", "v_speed_mps", "climb_mps")
        val iPitch = idx("pitch", "pitch_deg")
        val iRoll  = idx("roll", "roll_deg", "bank", "bank_deg")
        val iYaw   = idx("yaw", "yaw_deg", "heading", "heading_deg", "true_heading")

        val iX = idx("x")
        val iY = idx("y")
        val iZ = idx("z")

        data class RawRow(
            val tSec: Double,
            val lat: Double,
            val lon: Double,
            val alt: Double,
            val pitchDeg: Double?,
            val rollDeg: Double?,
            val yawDeg: Double?,
            val spdMps: Double?,
            val vsMps: Double?,
            val ax: Double?,
            val ay: Double?,
            val az: Double?
        )

        val rows = mutableListOf<RawRow>()
        var lastBaseSec = Double.NaN
        var sameSecondCounter = 0

        for (i in 1 until lines.size) {
            val parts = splitLineSmart(lines[i])
            if (parts.size <= maxOf(iTime, iLat, iLon, iAlt)) continue

            val baseSec = parseTimeToSec(parts[iTime]) ?: continue
            val tSec = if (baseSec == lastBaseSec) {
                sameSecondCounter++
                baseSec + sameSecondCounter * 0.1
            } else {
                sameSecondCounter = 0
                baseSec
            }
            lastBaseSec = baseSec

            val lat = toD(parts[iLat]) ?: continue
            val lon = toD(parts[iLon]) ?: continue
            val alt = toD(parts[iAlt]) ?: continue
            if (!lat.isValidLat() || !lon.isValidLon()) continue

            val spdFromFile = iSpd?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val vsFromFile  = iVs?.takeIf { it < parts.size }?.let { toD(parts[it]) }

            val pitchFromFile = iPitch?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val rollFromFile  = iRoll?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            val yawFromFile   = iYaw?.takeIf { it < parts.size }?.let { toD(parts[it]) }

            rows += RawRow(
                tSec = tSec,
                lat = lat,
                lon = lon,
                alt = alt,
                spdMps = spdFromFile,
                vsMps  = vsFromFile,
                pitchDeg = pitchFromFile,
                rollDeg = rollFromFile,
                yawDeg = yawFromFile,
                ax = iX?.takeIf { it < parts.size }?.let { toD(parts[it]) },
                ay = iY?.takeIf { it < parts.size }?.let { toD(parts[it]) },
                az = iZ?.takeIf { it < parts.size }?.let { toD(parts[it]) }
            )
        }

        if (rows.size < 2) return emptyList()

        val out = mutableListOf<FlightPoint>()
        var lastCourse = 0.0

        val maxSpeedKmh = 350.0
        val maxVs = 10.0

        for (i in rows.indices) {
            val r = rows[i]

            val rollDeg = when {
                r.rollDeg != null -> r.rollDeg
                (r.ay != null && r.az != null) -> Math.toDegrees(atan2(r.ay, r.az))
                else -> 0.0
            }

            val pitchHud = r.pitchDeg ?: 0.0

            if (i == 0) {
                val heading0 = r.yawDeg?.let { GeoMath.norm360(it) } ?: 0.0
                out += FlightPoint(
                    time = "0",
                    latitude = r.lat,
                    longitude = r.lon,
                    altitude = r.alt,
                    heading = heading0,
                    pitch = pitchHud,
                    roll = rollDeg,
                    dtSec = Double.NaN,
                    speedKmh = null,
                    vsMps = null,
                    yawDeg = heading0,
                    timeSource = TimeSource.REAL_TIMESTAMP
                )
                continue
            }

            val prev = rows[i - 1]
            val prevLL = LatLng(prev.lat, prev.lon)
            val curLL = LatLng(r.lat, r.lon)

            val distM = GeoMath.distanceMeters(prevLL, curLL)
            val dt = r.tSec - prev.tSec

            val dtOk = dt.isFinite() && dt >= 0.05
            val moved = distM >= 3.0
            val notTeleport = distM <= 80.0

            val course = if (moved && notTeleport) GeoMath.headingDegrees(prevLL, curLL) else lastCourse
            if (moved && notTeleport) lastCourse = course

            val speedKmhVal = if (dtOk && moved && notTeleport) (distM / dt) * 3.6 else Double.NaN
            val vs = if (dtOk) (r.alt - prev.alt) / dt else Double.NaN

            val speedKmhFromFile = r.spdMps?.takeIf { it.isFinite() && it >= 0.0 }?.times(3.6)
            val vsFromFile = r.vsMps?.takeIf { it.isFinite() }

            val speedKmhFinal = speedKmhFromFile
                ?: speedKmhVal.takeIf { it.isFinite() && it in 1.0..maxSpeedKmh }

            val vsFinal = vsFromFile
                ?: vs.takeIf { it.isFinite() && abs(it) <= maxVs }

            val headingVal = r.yawDeg?.let { GeoMath.norm360(it) } ?: GeoMath.norm360(course)

            out += FlightPoint(
                time = i.toString(),
                latitude = r.lat,
                longitude = r.lon,
                altitude = r.alt,
                heading = headingVal,
                pitch = pitchHud,
                roll = rollDeg,
                dtSec = dt,
                speedKmh = speedKmhFinal,
                vsMps = vsFinal,
                yawDeg = headingVal,
                timeSource = TimeSource.REAL_TIMESTAMP
            )
        }

        Log.i(TAG, "TXT/CSV parsed: points=${out.size}, header=${header.joinToString("|")}")
        return out
    }
}
