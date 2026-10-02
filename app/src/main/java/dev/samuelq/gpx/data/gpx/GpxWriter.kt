package dev.samuelq.gpx.data.gpx

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.bounds
import org.xmlpull.v1.XmlSerializer
import java.io.OutputStream
import java.time.format.DateTimeFormatter

const val GPX_MIME_TYPE = "application/gpx+xml"

/** Without the control characters XML 1.0 forbids even escaped, which a pasted name can carry. */
internal fun String.xmlSafe(): String = filterNot { it < ' ' && it != '\t' && it != '\n' && it != '\r' || it == '\uFFFE' || it == '\uFFFF' }

/**
 * Writes a [Track] as GPX 1.1, the on-disk format for recordings.
 *
 * @param newSerializer injected for plain-JVM tests, like [GpxParser]'s parser.
 */
class GpxWriter(private val newSerializer: () -> XmlSerializer = DEFAULT_SERIALIZER) {

    fun write(track: Track, out: OutputStream) {
        val xml = newSerializer()
        xml.setOutput(out, ENCODING)
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        xml.startDocument(ENCODING, null)

        xml.setPrefix("", NAMESPACE)
        xml.startTag(NAMESPACE, "gpx")
        xml.attribute(null, "version", "1.1")
        xml.attribute(null, "creator", CREATOR)

        val points = track.points
        // Waypoints are dropped at a fix, so the points' box holds them too.
        val bounds = points.bounds()
        if (bounds != null) {
            xml.startTag(NAMESPACE, "metadata")
            if (points.hasTime(0)) xml.textTag("time", timestamp(points.timeMillis(0)))
            xml.startTag(NAMESPACE, "bounds")
            xml.attribute(null, "minlat", format(bounds.southLatitude))
            xml.attribute(null, "minlon", format(bounds.westLongitude))
            xml.attribute(null, "maxlat", format(bounds.northLatitude))
            xml.attribute(null, "maxlon", format(bounds.eastLongitude))
            xml.endTag(NAMESPACE, "bounds")
            xml.endTag(NAMESPACE, "metadata")
        }

        // The schema orders wpt before trk.
        for (waypoint in track.waypoints) {
            xml.startTag(NAMESPACE, "wpt")
            xml.attribute(null, "lat", format(waypoint.point.latitude))
            xml.attribute(null, "lon", format(waypoint.point.longitude))
            waypoint.point.elevation?.let { xml.textTag("ele", oneDecimal(it)) }
            waypoint.point.time?.let { xml.textTag("time", TIMESTAMP.format(it)) }
            waypoint.name?.takeIf(String::isNotBlank)?.let { xml.textTag("name", it) }
            xml.endTag(NAMESPACE, "wpt")
        }

        xml.startTag(NAMESPACE, "trk")
        track.name?.takeIf(String::isNotBlank)?.let { xml.textTag("name", it) }

        for (segment in 0 until points.segmentCount) {
            xml.startTag(NAMESPACE, "trkseg")
            for (i in points.segmentStart(segment) until points.segmentEnd(segment)) {
                xml.startTag(NAMESPACE, "trkpt")
                // Six decimals is ~0.1 m, past what consumer GPS resolves.
                xml.attribute(null, "lat", format(points.latitude(i)))
                xml.attribute(null, "lon", format(points.longitude(i)))
                points.elevation(i).takeUnless(Float::isNaN)?.let { xml.textTag("ele", oneDecimal(it.toDouble())) }
                if (points.hasTime(i)) xml.textTag("time", timestamp(points.timeMillis(i)))
                xml.endTag(NAMESPACE, "trkpt")
            }
            xml.endTag(NAMESPACE, "trkseg")
        }

        xml.endTag(NAMESPACE, "trk")
        xml.endTag(NAMESPACE, "gpx")
        xml.endDocument()
        xml.flush()
    }

    private fun XmlSerializer.textTag(name: String, value: String) {
        startTag(NAMESPACE, name)
        text(value.xmlSafe())
        endTag(NAMESPACE, name)
    }

    private fun format(degrees: Double) = String.format(java.util.Locale.ROOT, "%.6f", degrees)

    private fun oneDecimal(value: Double) = String.format(java.util.Locale.ROOT, "%.1f", value)

    private fun timestamp(epochMillis: Long) = TIMESTAMP.format(java.time.Instant.ofEpochMilli(epochMillis))

    companion object {
        private const val ENCODING = "UTF-8"
        private const val NAMESPACE = "http://www.topografix.com/GPX/1/1"
        private const val CREATOR = "Offline GPX"

        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        private val DEFAULT_SERIALIZER: () -> XmlSerializer = {
            org.xmlpull.v1.XmlPullParserFactory.newInstance().newSerializer()
        }
    }
}
