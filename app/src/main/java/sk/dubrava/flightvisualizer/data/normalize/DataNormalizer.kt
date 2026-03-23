package sk.dubrava.flightvisualizer.data.normalize

import com.google.android.gms.maps.model.LatLng
import sk.dubrava.flightvisualizer.core.GeoMath
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import kotlin.math.abs

object DataNormalizer {

    private const val MAX_SPEED_MPS = 120.0
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

        // Garmin avionics: pitch/roll/yaw sú priama telemetria, EMA smoothing nie je potrebný
        return if (points.first().source == LogType.GARMIN_AVIONICS) derived
        else smoothHud(derived)
    }

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
                if (!dist.isFinite()) continue
                if (dist > MAX_STEP_DIST_M) {
                    // Skok väčší ako MAX_STEP_DIST_M — zahajujeme nový segment namiesto zahodenia bodu
                    out += p
                    last = p
                    continue
                }
            }

            out += p
            last = p
        }

        return out
    }

    private fun computeDerived(points: List<FlightPoint>): List<FlightPoint> {
        val out = mutableListOf<FlightPoint>()
        var lastYaw = 0.0

        var cumTime = 0.0
        var anchorIndex = 0
        var anchorTime = 0.0

        fun dtOf(p: FlightPoint): Double? =
            p.dtSec.takeIf { it.isFinite() && it > 0.0 }

        // Pre MSFS logy bez VS odhadujeme VS lineárnou regresiou výšky voči času
        val isMsfs = points.firstOrNull()?.source == LogType.MSFS
        val hasVsInLog = points.any { it.vsMps?.isFinite() == true }
        val msfsVsRegression: DoubleArray? =
            if (isMsfs && !hasVsInLog) estimateMsfsVsRegression(points, windowSec = 3.0) else null

        for (i in points.indices) {
            val p = points[i]

            if (i == 0) {
                val vs0 = p.vsMps?.takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }
                    ?: msfsVsRegression?.getOrNull(0)?.takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }
                out += p.copy(vsMps = vs0)
                p.yawDeg?.let { lastYaw = it }
                continue
            }

            val prev = points[i - 1]
            val dt = dtOf(p) ?: continue
            cumTime += dt

            val prevLL = LatLng(prev.latitude, prev.longitude)
            val curLL = LatLng(p.latitude, p.longitude)
            val dist = GeoMath.distanceMeters(prevLL, curLL)

            val computedYaw =
                if (dist.isFinite() && dist >= MIN_MOVE_M) GeoMath.headingDegrees(prevLL, curLL)
                else lastYaw
            lastYaw = computedYaw

            val vsClamped = p.vsMps?.takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }
            val vsMsfs = msfsVsRegression?.getOrNull(i)
                ?.takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }

            // Garmin avionics: yaw a speed pochádzajú priamo z avioniky, GPS výpočty sa nepoužívajú
            if (p.source == LogType.GARMIN_AVIONICS) {
                val yawOut = p.headingDeg ?: p.yawDeg ?: computedYaw
                out += p.copy(
                    yawDeg = yawOut,
                    headingDeg = p.headingDeg ?: yawOut,
                    speedMps = p.speedMps,
                    vsMps = vsClamped
                )
                continue
            }

            val speedOut: Double? = when (p.source) {
                LogType.MSFS, LogType.DRONE -> p.speedMps
                else -> {
                    if (p.speedMps != null) {
                        p.speedMps
                    } else {
                        while (anchorIndex < i) {
                            val nextTime = anchorTime + (dtOf(points[anchorIndex + 1]) ?: 0.0)
                            if (cumTime - nextTime <= 1.0) break
                            anchorIndex++
                            anchorTime = nextTime
                        }

                        val anchor = points[anchorIndex]
                        val anchorLL = LatLng(anchor.latitude, anchor.longitude)
                        val winDist = GeoMath.distanceMeters(anchorLL, curLL)
                        val winDt = (cumTime - anchorTime).coerceAtLeast(dt)

                        (winDist / winDt).takeIf { it.isFinite() && it <= MAX_SPEED_MPS }
                    }
                }
            }

            out += p.copy(
                yawDeg = p.yawDeg ?: computedYaw,
                headingDeg = p.headingDeg ?: computedYaw,
                speedMps = speedOut,
                vsMps = vsClamped ?: vsMsfs
            )
        }

        return out
    }

    /**
     * Odhaduje vertikálnu rýchlosť pre MSFS logy bez VS stĺpca.
     * Používa lineárnu regresiu výšky voči času v posuvnom okne [windowSec].
     * Vracia pole vsMps[i], kde NaN znamená nedostatok dát pre daný bod.
     */
    private fun estimateMsfsVsRegression(points: List<FlightPoint>, windowSec: Double): DoubleArray {
        val n = points.size
        val out = DoubleArray(n) { Double.NaN }
        if (n < 2) return out

        for (end in 0 until n) {
            val tEnd = points[end].tSec
            if (!tEnd.isFinite()) continue

            var i = end
            var count = 0
            var sumT = 0.0; var sumA = 0.0; var sumTT = 0.0; var sumTA = 0.0

            while (i >= 0) {
                val p = points[i]
                val t = p.tSec
                if (!t.isFinite()) break

                val dt = tEnd - t
                if (!dt.isFinite() || dt < 0.0) break
                if (dt > windowSec && count >= 6) break

                sumT += t; sumA += p.altitudeM; sumTT += t * t; sumTA += t * p.altitudeM
                count++
                i--
            }

            if (count < 6) continue

            val denom = count * sumTT - sumT * sumT
            if (!denom.isFinite() || abs(denom) < 1e-9) continue

            val slopeMps = (count * sumTA - sumT * sumA) / denom
            if (slopeMps.isFinite()) out[end] = slopeMps
        }

        return out
    }

    private fun smoothHud(points: List<FlightPoint>): List<FlightPoint> {
        if (points.size < 2) return points

        var sSpeed = points.first().speedMps ?: 0.0
        var sVs: Double? = points.first().vsMps
        var sYaw = points.first().yawDeg ?: 0.0
        var sPitch = points.first().pitchDeg
        var sRoll = points.first().rollDeg

        val out = mutableListOf<FlightPoint>()

        for (p in points) {
            p.speedMps?.let { sSpeed = ema(sSpeed, it, ALPHA_SPEED) }

            // MSFS VS je už z regresie — dvojitá EMA by zavádzala umelé oneskorenie
            if (p.source == LogType.MSFS) {
                sVs = p.vsMps ?: sVs
            } else {
                p.vsMps?.let { sVs = if (sVs != null) ema(sVs!!, it, ALPHA_VS) else it }
            }

            p.yawDeg?.let { sYaw = emaAngle(sYaw, it, ALPHA_YAW) }
            p.pitchDeg?.let { sPitch = if (sPitch != null) ema(sPitch!!, it, ALPHA_ATT) else it }
            p.rollDeg?.let { sRoll = if (sRoll != null) ema(sRoll!!, it, ALPHA_ATT) else it }

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

    private fun ema(prev: Double, x: Double, alpha: Double) = prev + alpha * (x - prev)

    private fun emaAngle(prev: Double, x: Double, alpha: Double): Double {
        var diff = (x - prev) % 360.0
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return (prev + alpha * diff + 360.0) % 360.0
    }
}