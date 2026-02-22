package sk.dubrava.flightvisualizer.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import sk.dubrava.flightvisualizer.core.GeoMath
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

class KmlFlightParser(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val TAG = "KmlFlightParser"

        private const val MIN_DT_SEC = 0.05
        private const val DEFAULT_DT_SEC = 0.20

        private const val JITTER_DIST_M = 3.0
        private const val MAX_STEP_DIST_M = 2000.0
        private const val MAX_SPEED_MPS = 70.0      // ~250 km/h
        private const val MAX_VS_MPS = 30.0

        // Pitch mapping from KML Camera tilt
        // Google Earth Camera tilt often represents "look angle", not aircraft pitch.
        // We'll map tilt->pitch, then auto-calibrate bias so "level" becomes ~0 deg.
        private const val PITCH_CLAMP_DEG = 30.0

        // How many early samples to estimate bias (level offset)
        private const val PITCH_BIAS_SAMPLES = 40
        private const val PITCH_BIAS_MAX_ABS = 45.0  // safety: ignore insane bias
    }

    fun parse(uri: Uri): List<FlightPoint> {
        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()

        return inputStream.use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)
            doc.documentElement.normalize()

            // ✅ Robust: try without namespace, then with namespace-aware
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
                val heading: Double?,
                val pitchRawDeg: Double?,   // before bias correction
                val rollDeg: Double?,
                val dtSec: Double?
            )

            fun Element.getFirstTextOrNullLocal(local: String): String? {
                // 1) no namespace
                val a = this.getElementsByTagName(local)
                if (a.length > 0) return a.item(0)?.textContent?.trim()

                // 2) any namespace
                val b = this.getElementsByTagNameNS("*", local)
                if (b.length > 0) return b.item(0)?.textContent?.trim()

                // 3) direct children fallback
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

            fun findFlyToElement(node: Node): Element? {
                var p: Node? = node.parentNode
                while (p != null) {
                    if (p is Element) {
                        val name = (p.localName ?: p.tagName).lowercase(Locale.ROOT)
                        if (name.endsWith("flyto")) return p
                    }
                    p = p.parentNode
                }
                return null
            }

            fun readDurationSec(flyTo: Element?): Double? {
                if (flyTo == null) return null

                // gx:duration (prefixed)
                val a = flyTo.getElementsByTagName("gx:duration")
                if (a.length > 0) return a.item(0).textContent.trim().toDoubleOrNull()

                // any namespace duration
                val b = flyTo.getElementsByTagNameNS("*", "duration")
                if (b.length > 0) return b.item(0).textContent.trim().toDoubleOrNull()

                // plain duration
                val c = flyTo.getElementsByTagName("duration")
                if (c.length > 0) return c.item(0).textContent.trim().toDoubleOrNull()

                return null
            }

            val raw = ArrayList<Raw>(cameraNodes.length)

            // 1️⃣ RAW pass
            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node is Element) {
                    val lat = node.getDoubleOrNullLocal("latitude") ?: continue
                    val lon = node.getDoubleOrNullLocal("longitude") ?: continue
                    val alt = node.getDoubleOrNullLocal("altitude") ?: 0.0

                    val heading = node.getDoubleOrNullLocal("heading")
                    val tilt = node.getDoubleOrNullLocal("tilt")
                    val rollCam = node.getDoubleOrNullLocal("roll")

                    // --- tilt -> pitch ---
                    // Variant A (your original): pitch = tilt - 90  (look-down => negative)
                    // Variant B (often nicer for "aircraft"): pitch = 90 - tilt
                    // We'll keep A, but auto-bias it to get level ~0.
                    val pitchRaw = tilt?.let { (it - 90.0).coerceIn(-PITCH_CLAMP_DEG, PITCH_CLAMP_DEG) }

                    val roll = rollCam?.coerceIn(-60.0, 60.0)

                    val flyTo = findFlyToElement(node)
                    val duration = readDurationSec(flyTo)

                    raw += Raw(
                        lat = lat,
                        lon = lon,
                        altM = alt,
                        heading = heading,
                        pitchRawDeg = pitchRaw,
                        rollDeg = roll,
                        dtSec = duration
                    )
                }
            }

            Log.i(TAG, "Raw points=${raw.size}")
            if (raw.size < 2) return@use emptyList()

            // 2️⃣ Pitch bias (auto-level)
            val pitchBias = run {
                val sample = raw.asSequence()
                    .mapNotNull { it.pitchRawDeg }
                    .take(PITCH_BIAS_SAMPLES)
                    .toList()

                if (sample.isEmpty()) 0.0
                else {
                    val avg = sample.average()
                    if (!avg.isFinite() || abs(avg) > PITCH_BIAS_MAX_ABS) 0.0 else avg
                }
            }

            if (abs(pitchBias) > 1.0) {
                Log.i(TAG, "Pitch bias applied: $pitchBias deg (level correction)")
            }

            // 3️⃣ Build FlightPoints
            val out = ArrayList<FlightPoint>(raw.size)
            var cumulativeTime = 0.0

            for (i in raw.indices) {
                val r = raw[i]

                val dt = r.dtSec?.takeIf { it.isFinite() && it >= MIN_DT_SEC } ?: DEFAULT_DT_SEC
                val tSec = cumulativeTime
                cumulativeTime += dt

                val pitch = r.pitchRawDeg?.let { (it - pitchBias).coerceIn(-PITCH_CLAMP_DEG, PITCH_CLAMP_DEG) }

                if (i == 0) {
                    out += FlightPoint(
                        tSec = tSec,
                        dtSec = dt,
                        latitude = r.lat,
                        longitude = r.lon,
                        altitudeM = r.altM,
                        speedMps = null,
                        vsMps = 0.0,
                        pitchDeg = pitch,
                        rollDeg = r.rollDeg,
                        yawDeg = r.heading,
                        headingDeg = r.heading,
                        source = LogType.KML
                        // ak máš LogType.KML, použi:
                        // source = LogType.KML
                    )
                } else {
                    val prev = raw[i - 1]

                    val distM = GeoMath.distanceMeters(
                        LatLng(prev.lat, prev.lon),
                        LatLng(r.lat, r.lon)
                    )

                    val usable = distM.isFinite() && distM <= MAX_STEP_DIST_M

                    val speedMps =
                        if (usable && distM >= JITTER_DIST_M)
                            (distM / dt).takeIf { it <= MAX_SPEED_MPS }
                        else null

                    val vs =
                        if (usable)
                            ((r.altM - prev.altM) / dt).takeIf { abs(it) <= MAX_VS_MPS }
                        else null

                    out += FlightPoint(
                        tSec = tSec,
                        dtSec = dt,
                        latitude = r.lat,
                        longitude = r.lon,
                        altitudeM = r.altM,
                        speedMps = speedMps,
                        vsMps = vs,
                        pitchDeg = pitch,
                        rollDeg = r.rollDeg,
                        yawDeg = r.heading,
                        headingDeg = r.heading,
                        source = LogType.GENERIC
                        // ak máš LogType.KML, použi:
                        // source = LogType.KML
                    )
                }
            }

            out
        }
    }
}
