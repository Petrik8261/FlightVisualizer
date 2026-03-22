package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import sk.dubrava.flightvisualizer.core.GeoMath
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.pow

class KmlFlightParser(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "KmlFlightParser"

        // gx:duration je animačný čas Google Earth kamery, nie reálny GPS interval.
        // WIW GPS logger pracuje na ~1 Hz → fixné dt = 1.0 s.
        private const val KML_ASSUMED_DT_SEC = 1.0

        // WIW KML začína od parkoviska/letiska — GE kamera "preletí" na štart trasy.
        // Tento intro skok filtrujeme ako artefakt exportu.
        private const val INTRO_JUMP_THRESHOLD_M = 5000.0

        private const val MAX_STEP_DIST_M = 500.0

        // Hard cap VS — krajné hodnoty sú GPS šum výšky, nie reálny stúp/klesanie.
        private const val MAX_VS_MPS = 30.0

        // Roll z KML je pohyb kamery sledujúcej lietadlo — pre GA koreluje s náklonmi.
        private const val ROLL_CLAMP_DEG = 45.0

        // WIW export posúva heading kamery o ~16° doľava oproti GPS tracku.
        // Empiricky zistené: medián (kml_heading − gps_track) = −16.4°, korelácia s roll = 0.03.
        private const val HEADING_CORRECTION_DEG = 16.0
    }

    fun parse(uri: Uri): List<FlightPoint> {
        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()

        return inputStream.use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)
            doc.documentElement.normalize()

            var cameraNodes: NodeList = doc.getElementsByTagName("Camera")
            if (cameraNodes.length < 2) {
                cameraNodes = doc.getElementsByTagNameNS("*", "Camera")
            }

            Log.i(TAG, "Camera nodes=${cameraNodes.length} root=${doc.documentElement.tagName}")
            if (cameraNodes.length < 2) return@use emptyList()

            data class Raw(
                val lat: Double,
                val lon: Double,
                val altM: Double,
                val headingDeg: Double?,
                val rollDeg: Double?
            )

            // Čítanie XML hodnôt nezávisle od namespace (WIW KML používa rôzne prefixové varianty)
            fun Element.getFirstTextOrNullLocal(local: String): String? {
                val a = this.getElementsByTagName(local)
                if (a.length > 0) return a.item(0)?.textContent?.trim()
                val b = this.getElementsByTagNameNS("*", local)
                if (b.length > 0) return b.item(0)?.textContent?.trim()
                val kids = this.childNodes
                for (i in 0 until kids.length) {
                    val n = kids.item(i)
                    if (n is Element) {
                        val ln = n.localName ?: n.tagName
                        if (ln.equals(local, ignoreCase = true)) return n.textContent?.trim()
                    }
                }
                return null
            }

            fun Element.getDoubleOrNullLocal(local: String): Double? =
                getFirstTextOrNullLocal(local)?.toDoubleOrNull()

            val allRaw = ArrayList<Raw>(cameraNodes.length)

            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node !is Element) continue

                val lat = node.getDoubleOrNullLocal("latitude") ?: continue
                val lon = node.getDoubleOrNullLocal("longitude") ?: continue
                val alt = node.getDoubleOrNullLocal("altitude") ?: 0.0

                val headingRaw = node.getDoubleOrNullLocal("heading")
                val heading = headingRaw?.let { ((it + HEADING_CORRECTION_DEG) % 360.0 + 360.0) % 360.0 }

                // tilt je uhol virtuálnej kamery (nie pitch lietadla) — ignorujeme
                // roll: WIW má opačnú sign konvenciu (záporné = pravý náklon) → negujeme
                val roll = node.getDoubleOrNullLocal("roll")
                    ?.let { -it }
                    ?.coerceIn(-ROLL_CLAMP_DEG, ROLL_CLAMP_DEG)

                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                allRaw += Raw(lat = lat, lon = lon, altM = alt, headingDeg = heading, rollDeg = roll)
            }

            Log.i(TAG, "Raw Camera points=${allRaw.size}")
            if (allRaw.size < 2) return@use emptyList()

            // GE kamera generuje viacero keyframeov na rovnakej GPS pozícii — deduplikujeme
            val deduped = ArrayList<Raw>(allRaw.size)
            for (r in allRaw) {
                val last = deduped.lastOrNull()
                if (last == null || !isSamePosition(last.lat, last.lon, r.lat, r.lon)) {
                    deduped += r
                }
            }

            Log.i(TAG, "After dedup=${deduped.size} (removed ${allRaw.size - deduped.size} duplicates)")
            if (deduped.size < 2) return@use emptyList()

            // WIW začína od parkoviska — preskočíme intro skok pred štartom letu
            var startIdx = 0
            for (i in 1 until deduped.size) {
                val dist = GeoMath.distanceMeters(
                    LatLng(deduped[i - 1].lat, deduped[i - 1].lon),
                    LatLng(deduped[i].lat, deduped[i].lon)
                )
                if (dist > INTRO_JUMP_THRESHOLD_M) {
                    startIdx = i
                    Log.i(TAG, "Intro jump ${dist.toInt()} m at index $i → flight starts here")
                    break
                }
            }

            val flight = deduped.subList(startIdx, deduped.size)
            Log.i(TAG, "Flight points after intro skip=${flight.size}")
            if (flight.size < 2) return@use emptyList()

            val out = ArrayList<FlightPoint>(flight.size)

            for (i in flight.indices) {
                val r     = flight[i]
                val tSec  = i * KML_ASSUMED_DT_SEC
                val dtSec = KML_ASSUMED_DT_SEC

                val vsMps: Double? = if (i == 0) {
                    null
                } else {
                    val prev = flight[i - 1]
                    val dist = GeoMath.distanceMeters(
                        LatLng(prev.lat, prev.lon),
                        LatLng(r.lat, r.lon)
                    )
                    if (dist > MAX_STEP_DIST_M) null
                    else {
                        val v = (r.altM - prev.altM) / KML_ASSUMED_DT_SEC
                        v.takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }
                    }
                }

                out += FlightPoint(
                    tSec       = tSec,
                    dtSec      = dtSec,
                    latitude   = r.lat,
                    longitude  = r.lon,
                    altitudeM  = r.altM,
                    speedMps   = null,
                    vsMps      = vsMps,
                    pitchDeg   = null,
                    rollDeg    = r.rollDeg,
                    yawDeg     = r.headingDeg,
                    headingDeg = r.headingDeg,
                    source     = LogType.KML
                )
            }

            Log.i(TAG, "KML parsed: points=${out.size}, dt=${KML_ASSUMED_DT_SEC}s, startIdx=$startIdx")
            out
        }
    }

    /**
     * Porovnáva dve GPS pozície s toleranciou ~1 m (5. desatinné miesto ≈ 1.1 m).
     */
    private fun isSamePosition(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val factor = 10.0.pow(5.0)
        return (Math.round(lat1 * factor) == Math.round(lat2 * factor)) &&
                (Math.round(lon1 * factor) == Math.round(lon2 * factor))
    }
}