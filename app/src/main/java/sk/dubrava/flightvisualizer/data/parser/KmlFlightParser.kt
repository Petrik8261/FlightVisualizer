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

class KmlFlightParser(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "KmlFlightParser"

        // gx:duration v WIW KML je animačný čas Google Earth kamery, NIE reálny GPS interval.
        // WIW GPS logger pracuje na ~1 Hz → používame pevné dt = 1.0 s.
        private const val KML_ASSUMED_DT_SEC = 1.0

        // Prvý bod WIW KML býva parkovisko/letisko, odkiaľ GE kamera "preletí" na štart trasy.
        // Tento intro skok filtrujeme ako vstupný artefakt, nie ako pohyb lietadla.
        private const val INTRO_JUMP_THRESHOLD_M = 5000.0

        // Maximálna vzdialenosť medzi susednými bodmi po štarte (bezpečnostný limit).
        private const val MAX_STEP_DIST_M = 500.0

        // Maximálna zobraziteľná VS — krajné hodnoty sú GPS šum výšky, nie reálny stúp/klesanie.
        // DataNormalizer to ďalej clampne a EMA-smoothuje, toto je hard cap v parseri.
        private const val MAX_VS_MPS = 30.0      // ~5900 fpm

        // Roll z KML je pohyb kamery, ktorá sleduje lietadlo. Pre GA koreluje s náklonmi.
        // Maximálny reálny náklon GA: ~30°, preto clampujeme.
        private const val ROLL_CLAMP_DEG = 45.0

        // Tilt v Camera elemente je uhol virtuálnej kamery (90° = horizont, >90° = dolu-dopredu).
        // NIE je to pitch lietadla → pitchDeg je vždy null.

        // WIW export systematicky posúva heading kamery o ~16° doľava oproti skutočnému GPS tracku.
        // Empiricky zistené analýzou záznamu: medián (kml_heading − gps_track) = −16.4°,
        // korelácia s roll = 0.03 → offset je konštantný, nie závislý od náklonu.
        // Korekcia: corrected = (raw + HEADING_CORRECTION_DEG + 360) % 360
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

            // -------------------------------------------------------------------------
            // Dátová trieda pre jeden surový Camera element
            // -------------------------------------------------------------------------
            data class Raw(
                val lat: Double,
                val lon: Double,
                val altM: Double,
                val headingDeg: Double?,
                val rollDeg: Double?
            )

            // -------------------------------------------------------------------------
            // Pomocné funkcie pre čítanie XML bez ohľadu na namespace
            // -------------------------------------------------------------------------
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

            // -------------------------------------------------------------------------
            // 1. Parsovanie Camera elementov → Raw body
            // -------------------------------------------------------------------------
            val allRaw = ArrayList<Raw>(cameraNodes.length)

            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node !is Element) continue

                val lat = node.getDoubleOrNullLocal("latitude") ?: continue
                val lon = node.getDoubleOrNullLocal("longitude") ?: continue
                val alt = node.getDoubleOrNullLocal("altitude") ?: 0.0

                val headingRaw = node.getDoubleOrNullLocal("heading")
                // Aplikuj konštantný offset +16° (WIW export bias)
                val heading = headingRaw?.let { ((it + HEADING_CORRECTION_DEG) % 360.0 + 360.0) % 360.0 }

                // tilt → NIE je pitch → ignorujeme ho úplne (viď komentár v companion)
                // roll → kamera sleduje lietadlo, pre GA koreluje s náklonmi
                val rollRaw = node.getDoubleOrNullLocal("roll")
                // WIW KML má opačnú sign konvenciu — záporné hodnoty sú pravý náklon, kladné ľavý.
                // Negáciou zosúladíme s konvenciou appky: kladné = pravý náklon (right bank).
                val roll = rollRaw?.let { -it }?.coerceIn(-ROLL_CLAMP_DEG, ROLL_CLAMP_DEG)

                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                allRaw += Raw(lat = lat, lon = lon, altM = alt, headingDeg = heading, rollDeg = roll)
            }

            Log.i(TAG, "Raw Camera points=${allRaw.size}")
            if (allRaw.size < 2) return@use emptyList()

            // -------------------------------------------------------------------------
            // 2. Deduplikácia: Google Earth kamera má viacero keyframeov na rovnakej
            //    GPS pozícii (kamera mení tilt/heading, lietadlo stojí). Ponecháme
            //    vždy prvý výskyt každej unikátnej pozície.
            // -------------------------------------------------------------------------
            val deduped = ArrayList<Raw>(allRaw.size)
            for (r in allRaw) {
                val last = deduped.lastOrNull()
                if (last == null ||
                    !isSamePosition(last.lat, last.lon, r.lat, r.lon)
                ) {
                    deduped += r
                }
            }

            Log.i(TAG, "After dedup=${deduped.size} (removed ${allRaw.size - deduped.size} duplicates)")
            if (deduped.size < 2) return@use emptyList()

            // -------------------------------------------------------------------------
            // 3. Preskoč intro skok: WIW začína od parkoviska/letiska a GE kamera
            //    preletí na miesto štartu letu. Tento skok NIE je pohybom lietadla.
            //    Nájdeme prvý bod za skokom väčším ako INTRO_JUMP_THRESHOLD_M.
            // -------------------------------------------------------------------------
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

            // -------------------------------------------------------------------------
            // 4. Zostrojenie FlightPoint zoznamu
            //
            //    dt = KML_ASSUMED_DT_SEC (1.0 s) — gx:duration je animačný čas GE kamery,
            //    nie GPS interval. WIW logger beží na ~1 Hz, preto 1 s je správny predpoklad.
            //
            //    speedMps = null → DataNormalizer dopočíta zo sliding window GPS dist/dt
            //    vsMps    = dAlt / dt (clamped) → DataNormalizer ďalej clampne + EMA smoothuje
            //    pitchDeg = null → tilt je uhol kamery, nie pitch lietadla
            //    rollDeg  = z KML (clamped) → aproximácia náklonu (nie IMU)
            //    source   = LogType.KML pre VŠETKY body (oprava: pôvodne bol bod 0 KML,
            //               ostatné GENERIC — to spôsobovalo nekonzistentné správanie v
            //               MainActivity a DataNormalizer kde sa vetví podľa source)
            // -------------------------------------------------------------------------
            val out = ArrayList<FlightPoint>(flight.size)

            for (i in flight.indices) {
                val r = flight[i]
                val tSec = i * KML_ASSUMED_DT_SEC
                val dtSec = if (i == 0) KML_ASSUMED_DT_SEC else KML_ASSUMED_DT_SEC

                val vsMps: Double? = if (i == 0) {
                    null
                } else {
                    val prev = flight[i - 1]
                    val dist = GeoMath.distanceMeters(
                        LatLng(prev.lat, prev.lon),
                        LatLng(r.lat, r.lon)
                    )
                    // Ak je krok príliš veľký (GPS outlier), VS nereportujeme
                    if (dist > MAX_STEP_DIST_M) {
                        null
                    } else {
                        val rawVs = (r.altM - prev.altM) / KML_ASSUMED_DT_SEC
                        rawVs.takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }
                    }
                }

                out += FlightPoint(
                    tSec = tSec,
                    dtSec = dtSec,
                    latitude = r.lat,
                    longitude = r.lon,
                    altitudeM = r.altM,
                    speedMps = null,          // DataNormalizer dopočíta zo GPS
                    vsMps = vsMps,            // clamped, DataNormalizer EMA smoothuje
                    pitchDeg = null,          // tilt = uhol kamery, nie IMU → vždy null
                    rollDeg = r.rollDeg,      // náklon je fyzikálna veličina nezávislá od zdroja
                    //                           (telefón aj lietadlo merajú rovnakú strankovú rotáciu)
                    yawDeg = r.headingDeg,
                    headingDeg = r.headingDeg,
                    source = LogType.KML      // ✅ všetky body → KML (nie GENERIC)
                )
            }

            Log.i(TAG, "KML parsed: points=${out.size}, dt=${KML_ASSUMED_DT_SEC}s, startIdx=$startIdx")
            out
        }
    }

    // -------------------------------------------------------------------------
    // Pomocné funkcie
    // -------------------------------------------------------------------------

    /**
     * Porovnáva dve GPS pozície s toleranciou ~1 m (5. desatinné miesto ≈ 1.1 m).
     * Používa sa na detekciu duplicitných Camera keyframeov.
     */
    private fun isSamePosition(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val PRECISION = 5  // desatinné miesta
        val factor = Math.pow(10.0, PRECISION.toDouble())
        return (Math.round(lat1 * factor) == Math.round(lat2 * factor)) &&
                (Math.round(lon1 * factor) == Math.round(lon2 * factor))
    }
}