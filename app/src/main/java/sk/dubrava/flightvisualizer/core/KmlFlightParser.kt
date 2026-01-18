package sk.dubrava.flightvisualizer.core

import android.content.ContentResolver
import android.net.Uri
import org.w3c.dom.Element
import sk.dubrava.flightvisualizer.data.model.FlightPoint
import sk.dubrava.flightvisualizer.data.model.TimeSource
import javax.xml.parsers.DocumentBuilderFactory
import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs

class KmlFlightParser(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val MIN_DT_SEC = 0.05
        private const val MAX_STEP_DIST_M = 2000.0
        private const val MAX_SPEED_KMH = 250.0
        private const val MAX_VS_MPS = 30.0

        private const val ALLOW_ESTIMATED_SPEED_FROM_KML_TOUR = false
    }

    fun parse(uri: Uri): List<FlightPoint> {
        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()

        return inputStream.use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)
            doc.documentElement.normalize()

            val cameraNodes = doc.getElementsByTagName("Camera")
            val raw = mutableListOf<FlightPoint>()

            fun Element.getDouble(tag: String): Double {
                val list = getElementsByTagName(tag)
                if (list.length == 0) return 0.0
                return list.item(0).textContent.trim().toDoubleOrNull() ?: 0.0
            }

            fun findFlyToElement(node: org.w3c.dom.Node): Element? {
                var p: org.w3c.dom.Node? = node.parentNode
                while (p != null) {
                    if (p is Element && p.tagName.endsWith("FlyTo")) return p
                    p = p.parentNode
                }
                return null
            }

            fun readDurationSec(flyTo: Element?): Double {
                if (flyTo == null) return Double.NaN
                val a = flyTo.getElementsByTagName("gx:duration")
                if (a.length > 0) return a.item(0).textContent.trim().toDoubleOrNull() ?: Double.NaN
                val b = flyTo.getElementsByTagName("duration")
                if (b.length > 0) return b.item(0).textContent.trim().toDoubleOrNull() ?: Double.NaN
                return Double.NaN
            }

            for (i in 0 until cameraNodes.length) {
                val node = cameraNodes.item(i)
                if (node is Element) {
                    val lon = node.getDouble("longitude")
                    val lat = node.getDouble("latitude")
                    val alt = node.getDouble("altitude")
                    val heading = node.getDouble("heading")
                    val tilt = node.getDouble("tilt")
                    val pitch = tilt - 90.0
                    val roll = node.getDouble("roll")

                    val flyTo = findFlyToElement(node)
                    val dt = readDurationSec(flyTo)

                    raw += FlightPoint(
                        time = i.toString(),
                        latitude = lat,
                        longitude = lon,
                        altitude = alt,
                        heading = heading,
                        pitch = pitch,
                        roll = roll,
                        dtSec = dt,
                        speedKmh = null,
                        vsMps = null,
                        yawDeg = null,
                        timeSource = TimeSource.KML_TOUR_DURATION
                    )
                }
            }

            if (raw.size < 2) return@use raw

            raw.mapIndexed { idx, fp ->
                if (idx == 0) {
                    fp.copy(yawDeg = fp.heading, vsMps = 0.0, speedKmh = null)
                } else {
                    val prev = raw[idx - 1]
                    val dt = fp.dtSec
                    val dtOk = dt.isFinite() && dt >= MIN_DT_SEC

                    val distM = GeoMath.distanceMeters(
                        LatLng(prev.latitude, prev.longitude),
                        LatLng(fp.latitude, fp.longitude)
                    )
                    val distOk = distM.isFinite() && distM <= MAX_STEP_DIST_M

                    val vs = if (dtOk && distOk) (fp.altitude - prev.altitude) / dt else null
                    val vsOk = vs != null && vs.isFinite() && abs(vs) <= MAX_VS_MPS

                    val canSpeed = dtOk && distOk && (
                            fp.timeSource == TimeSource.REAL_TIMESTAMP ||
                                    fp.timeSource == TimeSource.FIXED_RATE ||
                                    (ALLOW_ESTIMATED_SPEED_FROM_KML_TOUR && fp.timeSource == TimeSource.KML_TOUR_DURATION)
                            )

                    val spd = if (canSpeed) (distM / dt) * 3.6 else null
                    val spdOk = spd != null && spd.isFinite() && spd <= MAX_SPEED_KMH

                    fp.copy(
                        yawDeg = fp.heading,
                        vsMps = if (vsOk) vs else null,
                        speedKmh = if (spdOk) spd else null
                    )
                }
            }
        }
    }
}
