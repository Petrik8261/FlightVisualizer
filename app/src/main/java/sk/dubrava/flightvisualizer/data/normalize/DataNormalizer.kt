package sk.dubrava.flightvisualizer.data.normalize

import com.google.android.gms.maps.model.LatLng
import sk.dubrava.flightvisualizer.core.GeoMath
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import kotlin.math.abs

object DataNormalizer {

    private const val MAX_SPEED_MPS = 120.0      // ~430 km/h
    private const val MAX_VS_MPS = 30.0
    private const val MAX_STEP_DIST_M = 500.0
    private const val MIN_MOVE_M = 2.0

    private const val ALPHA_SPEED = 0.18
    private const val ALPHA_VS = 0.22
    private const val ALPHA_YAW = 0.20
    private const val ALPHA_ATT = 0.18

    fun normalize(points: List<FlightPoint>): List<FlightPoint> {
        if (points.size < 2) return points

        val cleaned = cleanGps(points)
        if (cleaned.size < 2) return cleaned

        val derived = computeDerived(cleaned)
        return smoothHud(derived)
    }

    // -------------------------------------------------------
    // 1️⃣ GPS cleaning
    // -------------------------------------------------------

    private fun cleanGps(points: List<FlightPoint>): List<FlightPoint> {
        val out = mutableListOf<FlightPoint>()

        var last: FlightPoint? = null

        for (p in points) {
            if (!p.latitude.isFinite() || !p.longitude.isFinite()) continue
            if (p.latitude !in -90.0..90.0 || p.longitude !in -180.0..180.0) continue

            if (last != null) {
                val dist = GeoMath.distanceMeters(
                    LatLng(last.latitude, last.longitude),
                    LatLng(p.latitude, p.longitude)
                )
                if (!dist.isFinite() || dist > MAX_STEP_DIST_M) continue
            }

            out += p
            last = p
        }

        return out
    }

    // -------------------------------------------------------
    // 2️⃣ Derived values (yaw, speed fallback, vs clamp)
    // -------------------------------------------------------

    private fun computeDerived(points: List<FlightPoint>): List<FlightPoint> {

        val out = mutableListOf<FlightPoint>()
        var lastYaw = 0.0

        for (i in points.indices) {

            val p = points[i]

            if (i == 0) {
                out += p
                continue
            }

            val prev = points[i - 1]
            val dt = p.dtSec.takeIf { it.isFinite() && it > 0 } ?: continue

            val prevLL = LatLng(prev.latitude, prev.longitude)
            val curLL = LatLng(p.latitude, p.longitude)

            val dist = GeoMath.distanceMeters(prevLL, curLL)

            val computedYaw =
                if (dist >= MIN_MOVE_M)
                    GeoMath.headingDegrees(prevLL, curLL)
                else lastYaw

            lastYaw = computedYaw

            val computedSpeed =
                if (p.speedMps == null && dist.isFinite())
                    (dist / dt).takeIf { it <= MAX_SPEED_MPS }
                else p.speedMps

            val vsClamped =
                p.vsMps?.takeIf { abs(it) <= MAX_VS_MPS }

            out += p.copy(
                yawDeg = p.yawDeg ?: computedYaw,
                headingDeg = p.headingDeg ?: computedYaw,
                speedMps = computedSpeed,
                vsMps = vsClamped
            )
        }

        return out
    }

    // -------------------------------------------------------
    // 3️⃣ EMA smoothing
    // -------------------------------------------------------

    private fun smoothHud(points: List<FlightPoint>): List<FlightPoint> {

        if (points.size < 2) return points

        var sSpeed = points.first().speedMps ?: 0.0
        var sVs = points.first().vsMps ?: 0.0
        var sYaw = points.first().yawDeg ?: 0.0
        var sPitch = points.first().pitchDeg
        var sRoll = points.first().rollDeg

        val out = mutableListOf<FlightPoint>()

        for (p in points) {

            p.speedMps?.let { sSpeed = ema(sSpeed, it, ALPHA_SPEED) }
            p.vsMps?.let { sVs = ema(sVs, it, ALPHA_VS) }

            p.yawDeg?.let { sYaw = emaAngle(sYaw, it, ALPHA_YAW) }

            p.pitchDeg?.let {
                sPitch = if (sPitch != null) ema(sPitch!!, it, ALPHA_ATT) else it
            }

            p.rollDeg?.let {
                sRoll = if (sRoll != null) ema(sRoll!!, it, ALPHA_ATT) else it
            }

            out += p.copy(
                speedMps = sSpeed,
                vsMps = sVs,
                yawDeg = sYaw,
                headingDeg = sYaw,
                pitchDeg = sPitch,
                rollDeg = sRoll
            )
        }

        return out
    }

    // -------------------------------------------------------

    private fun ema(prev: Double, x: Double, alpha: Double) =
        prev + alpha * (x - prev)

    private fun emaAngle(prev: Double, x: Double, alpha: Double): Double {
        var diff = (x - prev) % 360.0
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return (prev + alpha * diff + 360.0) % 360.0
    }
}




