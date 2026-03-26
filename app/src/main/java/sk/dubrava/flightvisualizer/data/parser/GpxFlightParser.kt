package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.time.Instant

/**
 * Parser pre GPX 1.0 a GPX 1.1 záznamy.
 *
 * Podporované aplikácie a ich špecifiká:
 *
 * ForeFlight:
 *   - Čistý GPX bez extensions: <trkpt lat lon><ele><time>
 *   - Altitude v metroch (GPX spec)
 *   - Bez speed/course
 *
 * Garmin Pilot:
 *   - Garmin TrackPointExtension v1/v2:
 *     <gpxtpx:TrackPointExtension><gpxtpx:speed>m/s</gpxtpx:speed><gpxtpx:course>deg</gpxtpx:course>
 *   - Altitude v metroch
 *
 * Air Navigation Pro:
 *   - Extensions s bare elementmi: <speed>m/s</speed>, <heading>deg</heading>
 *
 * GPX 1.0 (starší formát):
 *   - <course> a <speed> priamo v <trkpt>, nie v <extensions>
 *   - speed v m/s
 *
 * Spoločné problémy a riešenia:
 *   - Namespace variácie (gpxtpx:, gpxdata:, bare) → regex ignoruje prefix
 *   - Altitude v stopách (niektoré exporty) → detekcia cez max hodnotu
 *   - Speed v uzloch (niektoré exporty) → detekcia cez 90. percentil
 *   - Viacero <trkseg> → spájanie s časovými medzerami
 *   - Duplikátne body → deduplikácia podľa timestampu
 *   - GPS výškový šum → VS sa počíta v DataNormalizer regresiou cez 5s okno
 */
class GpxFlightParser(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "GpxFlightParser"

        private const val MAX_VS_MPS      = 30.0
        private const val MAX_ALT_METERS  = 8848.0   // Everest — ak ele > toto, sú stopy
        private const val FT_TO_M         = 0.3048
        private const val KT_TO_MPS       = 0.514444

        // Ak 90. percentil rýchlostí > tento prah, sú uzly (nie m/s)
        // 200 m/s = 720 km/h (nemožné pre GA), 200 kt = 103 m/s (rýchly turboprop)
        private const val SPEED_KNOTS_THRESHOLD = 200.0
    }

    fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use { parseFromString(it.bufferedReader().readText()) }
    }

    fun parseFromString(content: String): List<FlightPoint> {
        // Regex na extrakciu trkpt blokov — namespace-agnostický
        val trkptRegex = Regex(
            """<trkpt\b([^>]*)>(.*?)</trkpt>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        data class Raw(
            val tMs: Long,
            val lat: Double,
            val lon: Double,
            val altM: Double,
            val speedRaw: Double?,   // v pôvodných jednotkách (m/s alebo kt — detekujeme neskôr)
            val courseDeg: Double?
        )

        val rawPoints = ArrayList<Raw>(4096)

        for (match in trkptRegex.findAll(content)) {
            val attrs   = match.groupValues[1]
            val inner   = match.groupValues[2]

            val lat = extractAttr(attrs, "lat")?.toDoubleOrNull() ?: continue
            val lon = extractAttr(attrs, "lon")?.toDoubleOrNull() ?: continue

            if (!lat.isFinite() || !lon.isFinite()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val timeStr = extractElement(inner, "time") ?: continue
            val tMs     = parseIso8601ToMillis(timeStr) ?: continue

            val eleRaw  = extractElement(inner, "ele")?.toDoubleOrNull() ?: 0.0

            // speed: GPX 1.0 priamo, GPX 1.1 v extensions (ľubovoľný namespace prefix)
            val speedRaw = extractElement(inner, "speed")?.toDoubleOrNull()

            // course: GPX 1.0 priamo, Garmin gpxtpx:course, alebo heading
            val courseDeg = extractElement(inner, "course")?.toDoubleOrNull()
                ?: extractElement(inner, "heading")?.toDoubleOrNull()

            rawPoints += Raw(tMs, lat, lon, eleRaw, speedRaw, courseDeg)
        }

        if (rawPoints.size < 2) {
            Log.w(TAG, "No trkpt points found or too few (${rawPoints.size})")
            return emptyList()
        }

        // Zoradiť podľa času + odstrániť duplikáty rovnakého timestampu
        val sorted = rawPoints
            .sortedBy { it.tMs }
            .distinctBy { it.tMs }

        Log.i(TAG, "Raw trkpt: ${rawPoints.size} → after dedup: ${sorted.size}")
        if (sorted.size < 2) return emptyList()

        // Detekcia jednotiek výšky:
        // Ak max(ele) > výška Everestu, záznam používa stopy
        val maxEle = sorted.maxOf { it.altM }
        val altInFeet = maxEle > MAX_ALT_METERS
        if (altInFeet) Log.i(TAG, "Altitude detected as feet, converting to meters")

        // Detekcia jednotiek rýchlosti:
        // Ak 90. percentil speedRaw > prah, sú uzly
        val speeds = sorted.mapNotNull { it.speedRaw }.filter { it > 0.0 }.sorted()
        val speedInKnots = if (speeds.size >= 5) {
            val p90 = speeds[(speeds.size * 0.9).toInt().coerceAtMost(speeds.size - 1)]
            p90 > SPEED_KNOTS_THRESHOLD
        } else false
        if (speedInKnots) Log.i(TAG, "Speed detected as knots, converting to m/s")

        // Zostavenie FlightPoint
        val baseMs = sorted.first().tMs
        val out    = ArrayList<FlightPoint>(sorted.size)

        var prevAltM: Double? = null
        var prevTSec: Double? = null

        for (r in sorted) {
            val tSec  = (r.tMs - baseMs) / 1000.0
            val dtSec = prevTSec?.let { (tSec - it).coerceAtLeast(0.0) } ?: 0.0

            val altM  = if (altInFeet) r.altM * FT_TO_M else r.altM

            // VS z rozdielu výšok — DataNormalizer neskôr nahradí regresiou cez 5s okno
            val vsMps: Double? = if (prevTSec != null && prevAltM != null && dtSec > 0.0) {
                val v = (altM - prevAltM!!) / dtSec
                v.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_VS_MPS }
            } else null

            val speedMps = r.speedRaw
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { if (speedInKnots) it * KT_TO_MPS else it }

            val courseDeg = r.courseDeg
                ?.takeIf { it.isFinite() }
                ?.let {
                    var c = it % 360.0
                    if (c < 0.0) c += 360.0
                    c
                }

            out += FlightPoint(
                tSec       = tSec,
                dtSec      = dtSec,
                latitude   = r.lat,
                longitude  = r.lon,
                altitudeM  = altM,
                speedMps   = speedMps,
                vsMps      = vsMps,
                pitchDeg   = null,
                rollDeg    = null,
                yawDeg     = courseDeg,
                headingDeg = courseDeg,
                source     = LogType.GPX
            )

            prevTSec = tSec
            prevAltM = altM
        }

        Log.i(TAG, "GPX parsed: points=${out.size}, hasSpeed=${speeds.isNotEmpty()}, hasCourse=${sorted.any { it.courseDeg != null }}")
        return out
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    // Extrakcia atribútu z reťazca atribútov (lat="..." alebo lat='...')
    private fun extractAttr(attrs: String, name: String): String? =
        Regex("""$name\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)?.trim()

    // Extrakcia textového obsahu XML elementu s ľubovoľným namespace prefixom
    // Zachytí: <ele>v</ele>, <gpxtpx:speed>v</gpxtpx:speed>, <speed>v</speed>
    private fun extractElement(content: String, localName: String): String? =
        Regex("""<(?:[^:>\s]+:)?$localName\b[^>]*>([^<]*)<""", RegexOption.IGNORE_CASE)
            .find(content)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun parseIso8601ToMillis(s: String): Long? {
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(s + "Z").toEpochMilli() }
            catch (_: Exception) { null }
        }
    }
}