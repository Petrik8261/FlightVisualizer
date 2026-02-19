package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import org.w3c.dom.Element
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.LogType
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.max

class KmlFlightParser(
    private val contentResolver: ContentResolver
) {

    companion object {
        private const val MIN_DT_SEC = 0.05
        private const val DEFAULT_DT_SEC = 0.20
        private const val JITTER_DIST_M = 3.0
        private const val MAX_STEP_DIST_M = 2000.0
        private const val MAX_SPEED_MPS = 70.0      // ~250 km/h
        private const val MAX_VS_MPS = 30.0
    }

    fun parse(uri: Uri): List<FlightPoint> {

        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()

        return inputStream.use { stream ->

            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)
            doc.documentElement.normalize()

            val cameraNodes = doc.getElementsByTagName("Camera")
            if (cameraNodes.length < 2) return@use emptyList()

            data class Raw(
                val lat: Double,
                val lon: Double,
                val altM: Double,
                val heading: Double?,
                val pitch: Double?,
                val roll: Double?,
                val dtSec: Double?
            )

            val raw = mutableListOf<Raw>()

            fun Element.getDoubleOrNull(tag: String): Double? {
                val list = getElementsByTagName(tag)
                if (list.length == 0) return null
                return list.item(0).textContent.trim().toDoubleOrNull()
            }

            fun findFlyToElement(node: org.w3c.dom.Node): Element? {
                var p: org.w3c.dom.Node? = node.parentNode
                while (p != null) {
                    if (p is Element && p.tagName.endsWith("FlyTo")) return p
                    p = p.parentNode
                }
                return null
            }

            fun readDurationSec(flyTo: Element?): Double? {
                if (flyTo == null) return null
                val a = flyTo.getElementsByTagName("gx:duration")
                if (a.length > 0) return a.item(0).textContent.trim().toDoubleOrNull()
                val b = flyTo.getElementsByTagName("duration")
                if (b.length > 0) return b.item(0).textContent.trim().toDoubleOrNull()
                return null
            }

            // 1️⃣ RAW
            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node is Element) {

                    val lat = node.getDoubleOrNull("latitude") ?: continue
                    val lon = node.getDoubleOrNull("longitude") ?: continue
                    val alt = node.getDoubleOrNull("altitude") ?: 0.0

                    val heading = node.getDoubleOrNull("heading")
                    val tilt = node.getDoubleOrNull("tilt")
                    val rollCam = node.getDoubleOrNull("roll")

                    val pitch = tilt?.let { (it - 90.0).coerceIn(-30.0, 30.0) }
                    val roll = rollCam?.coerceIn(-60.0, 60.0)

                    val flyTo = findFlyToElement(node)
                    val duration = readDurationSec(flyTo)

                    raw += Raw(
                        lat = lat,
                        lon = lon,
                        altM = alt,
                        heading = heading,
                        pitch = pitch,
                        roll = roll,
                        dtSec = duration
                    )
                }
            }

            if (raw.size < 2) return@use emptyList()

            val out = mutableListOf<FlightPoint>()

            var cumulativeTime = 0.0

            for (i in raw.indices) {

                val r = raw[i]

                val dt = r.dtSec?.takeIf { it.isFinite() && it >= MIN_DT_SEC }
                    ?: DEFAULT_DT_SEC

                val tSec = cumulativeTime
                cumulativeTime += dt

                if (i == 0) {
                    out += FlightPoint(
                        tSec = tSec,
                        dtSec = dt,
                        latitude = r.lat,
                        longitude = r.lon,
                        altitudeM = r.altM,
                        speedMps = null,
                        vsMps = 0.0,
                        pitchDeg = r.pitch,
                        rollDeg = r.roll,
                        yawDeg = r.heading,
                        headingDeg = r.heading,
                        source = LogType.GENERIC
                    )
                } else {

                    val prev = raw[i - 1]

                    val distM = GeoMath.distanceMeters(
                        LatLng(prev.lat, prev.lon),
                        LatLng(r.lat, r.lon)
                    )

                    val speedMps =
                        if (distM >= JITTER_DIST_M)
                            (distM / dt).takeIf { it <= MAX_SPEED_MPS }
                        else null

                    val vs =
                        ((r.altM - prev.altM) / dt)
                            .takeIf { abs(it) <= MAX_VS_MPS }

                    out += FlightPoint(
                        tSec = tSec,
                        dtSec = dt,
                        latitude = r.lat,
                        longitude = r.lon,
                        altitudeM = r.altM,
                        speedMps = speedMps,
                        vsMps = vs,
                        pitchDeg = r.pitch,
                        rollDeg = r.roll,
                        yawDeg = r.heading,
                        headingDeg = r.heading,
                        source = LogType.GENERIC
                    )
                }
            }

            out
        }
    }
}


