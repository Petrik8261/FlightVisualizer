package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.time.Instant

/**
 * Parser pre SkyDemon KML záznamy vo formáte gx:Track.
 *
 * Štruktúra SkyDemon KML:
 *   <gx:Track>
 *     <when>2023-08-15T17:11:00Z</when>
 *     ...
 *     <gx:coord>18.123456 48.123456 450.0</gx:coord>   ← LON LAT ALT (!)
 *     ...
 *   </gx:Track>
 *
 * Poznámky:
 *   - gx:coord má poradie: longitude latitude altitude (nie lat/lon!)
 *   - <when> je ISO 8601 UTC timestamp
 *   - SkyDemon neloguje pitch, roll ani heading
 *   - source = LogType.KML_TRACK → HDG = CRS v MainActivity (bez lagu)
 */
class SkyDemonKmlParser(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG        = "SkyDemonKmlParser"
        private const val MAX_VS_MPS = 30.0
    }

    fun parse(uri: Uri): List<FlightPoint> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use { parseFromString(it.bufferedReader().readText()) }
    }

    fun parseFromString(content: String): List<FlightPoint> {
        val whenRegex  = Regex("""<when>\s*(.*?)\s*</when>""")
        val coordRegex = Regex("""<gx:coord>\s*(.*?)\s*</gx:coord>""")

        val whenTimes = whenRegex.findAll(content).map { it.groupValues[1] }.toList()
        val coords    = coordRegex.findAll(content).map { it.groupValues[1] }.toList()

        if (whenTimes.isEmpty() || coords.isEmpty()) {
            Log.w(TAG, "No gx:Track data (when=${whenTimes.size}, coord=${coords.size})")
            return emptyList()
        }

        if (whenTimes.size != coords.size)
            Log.w(TAG, "when/coord mismatch: ${whenTimes.size} vs ${coords.size} — truncating")

        val count = minOf(whenTimes.size, coords.size)

        data class Raw(val tMs: Long, val lat: Double, val lon: Double, val altM: Double)

        val rawPoints = ArrayList<Raw>(count)

        for (i in 0 until count) {
            val tMs   = parseIso8601ToMillis(whenTimes[i]) ?: continue
            val parts = coords[i].trim().split(Regex("\\s+"))
            if (parts.size < 2) continue

            // gx:coord poradie: LON LAT ALT
            val lon  = parts[0].toDoubleOrNull() ?: continue
            val lat  = parts[1].toDoubleOrNull() ?: continue
            val altM = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0

            if (!lat.isFinite() || !lon.isFinite()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            rawPoints += Raw(tMs, lat, lon, altM)
        }

        val sortedRaw = rawPoints
            .sortedBy { it.tMs }
            .distinctBy { it.tMs }

        val normalizedRaw = ArrayList<Raw>(sortedRaw.size)
        for (r in sortedRaw) {
            val prev = normalizedRaw.lastOrNull()
            if (prev == null) { normalizedRaw.add(r); continue }
            val dtMs = r.tMs - prev.tMs
            // Preskočí sub-sekundové artefakty (SkyDemon GPS aktualizuje ~1 Hz)
            if (dtMs < 500L) continue
            // Preskočí body kde sa GPS pozícia nezmenila
            if (r.lat == prev.lat && r.lon == prev.lon) continue
            normalizedRaw.add(r)
        }

        Log.i(TAG, "Raw gx:Track points: ${normalizedRaw.size} / $count")
        if (normalizedRaw.size < 2) return emptyList()

        val baseMs = normalizedRaw.first().tMs
        val out    = ArrayList<FlightPoint>(normalizedRaw.size)

        var prevAltM: Double? = null
        var prevTSec: Double? = null

        for (r in normalizedRaw) {
            val tSec  = (r.tMs - baseMs) / 1000.0
            val dtSec = prevTSec?.let { (tSec - it).coerceAtLeast(0.0) } ?: 0.0

            val vsMps: Double? = if (prevTSec != null && prevAltM != null && dtSec > 0.0) {
                val v = (r.altM - prevAltM!!) / dtSec
                v.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_VS_MPS }
            } else null

            out += FlightPoint(
                tSec       = tSec,
                dtSec      = dtSec,
                latitude   = r.lat,
                longitude  = r.lon,
                altitudeM  = r.altM,
                speedMps   = null,
                vsMps      = vsMps,
                pitchDeg   = null,
                rollDeg    = null,
                yawDeg     = null,
                headingDeg = null,
                source     = LogType.KML_TRACK
            )

            prevTSec = tSec
            prevAltM = r.altM
        }

        Log.i(TAG, "SkyDemon KML parsed: points=${out.size}")
        return out
    }

    private fun parseIso8601ToMillis(s: String): Long? {
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(s + "Z").toEpochMilli() }
            catch (_: Exception) { null }
        }
    }
}