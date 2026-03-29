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

        private const val KML_ASSUMED_DT_SEC     = 1.0
        private const val INTRO_JUMP_THRESHOLD_M = 5000.0
        private const val MAX_STEP_DIST_M        = 500.0
        private const val MAX_VS_MPS             = 30.0
        private const val ROLL_CLAMP_DEG         = 45.0
        private const val PITCH_CLAMP_DEG        = 25.0

        // Minimálna vzdialenosť pohybu pre zahrnutie bodu do kalibrácie headingu.
        // Body kde GE kamera iba panuje bez pohybu lietadla sa vynechajú.
        private const val MIN_MOVE_FOR_CALIB_M = 3.0
        private const val MIN_CALIB_SAMPLES    = 20
    }

    private data class Raw(
        val lat:        Double,
        val lon:        Double,
        val altM:       Double,
        val headingRaw: Double?,
        val tiltRaw:    Double?,
        val rollRaw:    Double?
    )

    fun parse(uri: Uri): List<FlightPoint> {
        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()

        return inputStream.use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc     = builder.parse(stream)
            doc.documentElement.normalize()

            var cameraNodes: NodeList = doc.getElementsByTagName("Camera")
            if (cameraNodes.length < 2)
                cameraNodes = doc.getElementsByTagNameNS("*", "Camera")

            Log.i(TAG, "Camera nodes=${cameraNodes.length}")
            if (cameraNodes.length < 2) return@use emptyList()

            // -------------------------------------------------------------------------
            // 1. Parsovanie Camera elementov — surové hodnoty bez korekcie
            // -------------------------------------------------------------------------
            val allRaw = ArrayList<Raw>(cameraNodes.length)

            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node !is Element) continue

                val lat = node.getDoubleOrNull("latitude")  ?: continue
                val lon = node.getDoubleOrNull("longitude") ?: continue
                val alt = node.getDoubleOrNull("altitude")  ?: 0.0

                if (!lat.isFinite() || !lon.isFinite() ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                allRaw += Raw(
                    lat        = lat,
                    lon        = lon,
                    altM       = alt,
                    headingRaw = node.getDoubleOrNull("heading"),
                    tiltRaw    = node.getDoubleOrNull("tilt"),
                    rollRaw    = node.getDoubleOrNull("roll")
                )
            }

            Log.i(TAG, "Raw Camera points=${allRaw.size}")
            if (allRaw.size < 2) return@use emptyList()

            // -------------------------------------------------------------------------
            // 2. Deduplikácia
            // -------------------------------------------------------------------------
            val deduped = ArrayList<Raw>(allRaw.size)
            for (r in allRaw) {
                val last = deduped.lastOrNull()
                if (last == null || !isSamePosition(last.lat, last.lon, r.lat, r.lon))
                    deduped += r
            }

            Log.i(TAG, "After dedup=${deduped.size}")
            if (deduped.size < 2) return@use emptyList()

            // -------------------------------------------------------------------------
            // 3. Intro skok
            // -------------------------------------------------------------------------
            var startIdx = 0
            for (i in 1 until deduped.size) {
                val dist = GeoMath.distanceMeters(
                    LatLng(deduped[i - 1].lat, deduped[i - 1].lon),
                    LatLng(deduped[i].lat,     deduped[i].lon)
                )
                if (dist > INTRO_JUMP_THRESHOLD_M) {
                    startIdx = i
                    Log.i(TAG, "Intro jump ${dist.toInt()} m at index $i")
                    break
                }
            }

            val flight = deduped.subList(startIdx, deduped.size)
            Log.i(TAG, "Flight points after intro skip=${flight.size}")
            if (flight.size < 2) return@use emptyList()

            // -------------------------------------------------------------------------
            // 4. Auto-kalibrácia heading offsetu
            //
            // Pre každý bod kde je reálny pohyb vypočítame rozdiel medzi KML headingom
            // a GPS trackom z lat/lon súradníc. Medián týchto rozdielov je systematický
            // offset spôsobený konkrétnym upevnením mobilu v danom lietadle.
            // Tým sa zbavíme hardcoded konštanty a parser funguje pre ľubovoľné upevnenie.
            // -------------------------------------------------------------------------
            val headingOffset = calibrateHeadingOffset(flight)
            Log.i(TAG, "Heading offset: ${String.format("%.1f", headingOffset)}°")

            // Pitch auto-kalibrácia: medián (tilt - 90) cez celý záznam predstavuje
            // systematický offset daný uhlom upevnenia kamery v lietadle.
            // Odčítaním tohto offsetu centrujeme pitch na 0° pre typický letový postoj.
            val pitchOffset = calibratePitchOffset(flight)
            Log.i(TAG, "Pitch offset: ${String.format("%.1f", pitchOffset)}°")

            // -------------------------------------------------------------------------
            // 5. Zostrojenie FlightPoint
            //
            // tilt → pitch aproximácia:
            //   tilt = 90° → kamera sleduje horizont → pitch ≈ 0°
            //   tilt > 90° → kamera hľadí dolu-dopredu → nos hore → pitch > 0°
            //   tilt < 90° → kamera hľadí hore → nos dole → pitch < 0°
            //   vzorec: pitch = (tilt - 90) - pitchOffset (clamp ±PITCH_CLAMP_DEG)
            //
            // Ide o aproximáciu z pohybu kamery, nie IMU meranie.
            // V ASSISTED móde sa zobrazí s EST badge.
            // V RAW móde FlightLoader pitch nulluje.
            // -------------------------------------------------------------------------
            val out = ArrayList<FlightPoint>(flight.size)

            for (i in flight.indices) {
                val r    = flight[i]
                val tSec = i * KML_ASSUMED_DT_SEC

                val vsMps: Double? = if (i == 0) null else {
                    val prev = flight[i - 1]
                    val dist = GeoMath.distanceMeters(
                        LatLng(prev.lat, prev.lon),
                        LatLng(r.lat,    r.lon)
                    )
                    if (dist > MAX_STEP_DIST_M) null
                    else (r.altM - prev.altM).div(KML_ASSUMED_DT_SEC)
                        .takeIf { it.isFinite() && abs(it) <= MAX_VS_MPS }
                }

                val heading = r.headingRaw?.let {
                    ((it + headingOffset) % 360.0 + 360.0) % 360.0
                }

                val pitch = r.tiltRaw?.let {
                    (it - 90.0 - pitchOffset).coerceIn(-PITCH_CLAMP_DEG, PITCH_CLAMP_DEG)
                }

                // WIW má opačnú sign konvenciu rollu — negáciou zosúladíme s konvenciou appky
                val roll = r.rollRaw?.let { -it }?.coerceIn(-ROLL_CLAMP_DEG, ROLL_CLAMP_DEG)

                out += FlightPoint(
                    tSec       = tSec,
                    dtSec      = KML_ASSUMED_DT_SEC,
                    latitude   = r.lat,
                    longitude  = r.lon,
                    altitudeM  = r.altM,
                    speedMps   = null,
                    vsMps      = vsMps,
                    pitchDeg   = pitch,
                    rollDeg    = roll,
                    yawDeg     = heading,
                    headingDeg = heading,
                    source     = LogType.KML
                )
            }

            Log.i(TAG, "KML parsed: points=${out.size}")
            out
        }
    }

    // -------------------------------------------------------------------------
    // Auto-kalibrácia: medián rozdielu (kml_heading − gps_track)
    // -------------------------------------------------------------------------
    private fun calibrateHeadingOffset(flight: List<Raw>): Double {
        val diffs = ArrayList<Double>(flight.size)

        for (i in 1 until flight.size) {
            val prev       = flight[i - 1]
            val curr       = flight[i]
            val kmlHeading = curr.headingRaw ?: continue

            val dist = GeoMath.distanceMeters(
                LatLng(prev.lat, prev.lon),
                LatLng(curr.lat, curr.lon)
            )
            if (dist < MIN_MOVE_FOR_CALIB_M) continue

            val gpsTrack = GeoMath.headingDegrees(
                LatLng(prev.lat, prev.lon),
                LatLng(curr.lat, curr.lon)
            )

            var diff = kmlHeading - gpsTrack
            while (diff >  180.0) diff -= 360.0
            while (diff < -180.0) diff += 360.0

            diffs += diff
        }

        if (diffs.size < MIN_CALIB_SAMPLES) {
            Log.w(TAG, "Not enough samples for heading calibration (${diffs.size}), offset=0°")
            return 0.0
        }

        diffs.sort()
        val median = if (diffs.size % 2 == 0)
            (diffs[diffs.size / 2 - 1] + diffs[diffs.size / 2]) / 2.0
        else
            diffs[diffs.size / 2]

        return -median
    }

    // -------------------------------------------------------------------------
    // Pitch auto-kalibrácia: medián (tilt - 90) cez celý záznam
    // -------------------------------------------------------------------------
    private fun calibratePitchOffset(flight: List<Raw>): Double {
        val values = flight.mapNotNull { it.tiltRaw?.let { t -> t - 90.0 } }
        if (values.size < 10) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0)
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        else
            sorted[sorted.size / 2]
    }

    // -------------------------------------------------------------------------
    // XML helpers — namespace-agnostické čítanie elementov
    // -------------------------------------------------------------------------
    private fun Element.getDoubleOrNull(local: String): Double? {
        val a = this.getElementsByTagName(local)
        if (a.length > 0) return a.item(0)?.textContent?.trim()?.toDoubleOrNull()
        val b = this.getElementsByTagNameNS("*", local)
        if (b.length > 0) return b.item(0)?.textContent?.trim()?.toDoubleOrNull()
        val kids = this.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element) {
                val ln = n.localName ?: n.tagName
                if (ln.equals(local, ignoreCase = true))
                    return n.textContent?.trim()?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun isSamePosition(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val factor = 100_000.0
        return (Math.round(lat1 * factor) == Math.round(lat2 * factor)) &&
                (Math.round(lon1 * factor) == Math.round(lon2 * factor))
    }
}