package dev.samuelq.gpx.data.gpx

import dev.samuelq.gpx.core.model.Track
import org.xmlpull.v1.XmlSerializer
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Writes a [Track] as GPX 1.1 - storage, not an export feature. A recording *is* a GPX
 * file on disk, so what this writes is what the app reads back and what the user shares.
 *
 * @param newSerializer injected for the same reason [GpxParser] injects its parser: the
 *   framework's xmlpull classes are unimplemented stubs in unit tests.
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

        val startedAt = track.segments.firstOrNull()?.points?.firstOrNull()?.time
        if (startedAt != null) {
            xml.startTag(NAMESPACE, "metadata")
            xml.textTag("time", TIMESTAMP.format(startedAt))
            xml.endTag(NAMESPACE, "metadata")
        }

        xml.startTag(NAMESPACE, "trk")
        track.name?.takeIf(String::isNotBlank)?.let { xml.textTag("name", it) }

        for (segment in track.segments) {
            if (segment.points.isEmpty()) continue
            xml.startTag(NAMESPACE, "trkseg")
            for (point in segment.points) {
                xml.startTag(NAMESPACE, "trkpt")
                // Six decimals is ~0.1 m at the equator - past what consumer GPS resolves,
                // and it keeps a long ride's file from doubling in size for noise.
                xml.attribute(null, "lat", format(point.latitude))
                xml.attribute(null, "lon", format(point.longitude))
                point.elevation?.let { xml.textTag("ele", String.format(java.util.Locale.ROOT, "%.1f", it)) }
                point.time?.let { xml.textTag("time", TIMESTAMP.format(it)) }
                // Not a true dilution-of-precision figure - see TrackPoint.accuracyMeters -
                // but the closest slot GPX has, and after `<time>` is where the schema puts it.
                point.accuracyMeters?.let { xml.textTag("hdop", String.format(java.util.Locale.ROOT, "%.1f", it)) }
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
        text(value)
        endTag(NAMESPACE, name)
    }

    private fun format(degrees: Double) = String.format(java.util.Locale.ROOT, "%.6f", degrees)

    companion object {
        private const val ENCODING = "UTF-8"
        private const val NAMESPACE = "http://www.topografix.com/GPX/1/1"
        private const val CREATOR = "Offline GPX"

        /** GPX 1.1 wants ISO 8601 in UTC, which is what [Instant] renders by default. */
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        private val DEFAULT_SERIALIZER: () -> XmlSerializer = {
            org.xmlpull.v1.XmlPullParserFactory.newInstance().newSerializer()
        }
    }
}
