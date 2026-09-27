package dev.samuelq.gpx.data.gpx

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackSegment
import dev.samuelq.gpx.core.model.Waypoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streaming GPX reader. Pull-parses rather than building a DOM: a long ride is tens of
 * thousands of points and nothing needs random access to the XML.
 *
 * Tolerant by design - GPX in the wild mixes 1.0 and 1.1 namespaces, omits `<ele>`, spells
 * timestamps several ways and carries vendor `<extensions>`. Anything unrecognised is
 * skipped; only a document that is not GPX at all is an error.
 *
 * @param newPullParser injected so this is testable on a plain JVM, where the framework's
 *   xmlpull classes are unimplemented stubs.
 */
class GpxParser(private val newPullParser: () -> XmlPullParser = DEFAULT_PULL_PARSER) {

    fun parse(input: InputStream): Track {
        val parser = try {
            newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                // FEATURE_PROCESS_DOCDECL stays false by default, so an internal DTD is
                // never expanded - that closes off entity-expansion attacks.
                setInput(input, null) // null: take the encoding from the XML declaration
            }
        } catch (e: XmlPullParserException) {
            throw GpxParseException("Could not start an XML parser", e)
        }

        try {
            parser.nextTag()
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != TAG_GPX) {
                throw GpxParseException("Root element is <${parser.name}>, expected <gpx>")
            }
            return readGpx(parser)
        } catch (e: XmlPullParserException) {
            throw GpxParseException("Malformed XML: ${e.message}", e)
        }
    }

    private fun readGpx(parser: XmlPullParser): Track {
        val segments = mutableListOf<TrackSegment>()
        val waypoints = mutableListOf<Waypoint>()
        var trackName: String? = null
        var metadataName: String? = null
        var trackDescription: String? = null

        forEachChild(parser) {
            when (parser.name) {
                TAG_WPT -> readWaypoint(parser)?.let(waypoints::add)

                TAG_METADATA -> forEachChild(parser) {
                    when (parser.name) {
                        TAG_NAME -> metadataName = readText(parser).takeIf(String::isNotBlank)
                        else -> skip(parser)
                    }
                }

                TAG_TRK -> forEachChild(parser) {
                    when (parser.name) {
                        TAG_NAME -> {
                            val value = readText(parser).takeIf(String::isNotBlank)
                            // First named track wins; later ones are usually laps.
                            if (trackName == null) trackName = value
                        }

                        TAG_DESC -> {
                            val value = readText(parser).takeIf(String::isNotBlank)
                            if (trackDescription == null) trackDescription = value
                        }

                        TAG_TRKSEG -> readSegment(parser)?.let(segments::add)
                        else -> skip(parser)
                    }
                }

                // A route has no timestamps, but it is still a polyline with elevation,
                // so it is worth showing rather than reporting the file as empty.
                TAG_RTE -> {
                    var routeName: String? = null
                    val routePoints = mutableListOf<TrackPoint>()
                    forEachChild(parser) {
                        when (parser.name) {
                            TAG_NAME -> routeName = readText(parser).takeIf(String::isNotBlank)
                            TAG_RTEPT -> readPoint(parser)?.let(routePoints::add)
                            else -> skip(parser)
                        }
                    }
                    if (routePoints.isNotEmpty()) segments += TrackSegment(routePoints)
                    if (trackName == null) trackName = routeName
                }

                else -> skip(parser)
            }
        }

        return Track(
            name = trackName ?: metadataName,
            segments = segments.filter { it.points.isNotEmpty() },
            description = trackDescription,
            waypoints = waypoints,
        )
    }

    /** Reads a `<wpt>`. Returns null - rather than throwing - if lat/lon are unusable. */
    private fun readWaypoint(parser: XmlPullParser): Waypoint? {
        val latitude = parser.getAttributeValue(null, ATTR_LAT)?.toDoubleOrNull()
        val longitude = parser.getAttributeValue(null, ATTR_LON)?.toDoubleOrNull()

        var elevation: Double? = null
        var time: Instant? = null
        var description: String? = null

        forEachChild(parser) {
            when (parser.name) {
                TAG_ELE -> elevation = readText(parser).trim().toDoubleOrNull()
                TAG_TIME -> time = parseGpxTime(readText(parser))
                TAG_DESC -> description = readText(parser).takeIf(String::isNotBlank)
                else -> skip(parser)
            }
        }

        if (latitude == null || longitude == null) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Waypoint(TrackPoint(latitude, longitude, elevation, time), description)
    }

    private fun readSegment(parser: XmlPullParser): TrackSegment? {
        val depth = parser.depth
        val points = mutableListOf<TrackPoint>()
        forEachChild(parser) {
            when (parser.name) {
                TAG_TRKPT -> readPoint(parser)?.let {
                    if (points.size < MAX_POINTS_PER_SEGMENT) {
                        points += it
                    } else {
                        // forEachChild is inline, so this returns from readSegment itself
                        // rather than just the lambda, leaving the parser on this
                        // <trkseg>'s own END_TAG exactly as a normal exit would.
                        skipRest(parser, depth)
                        return TrackSegment(points)
                    }
                }

                else -> skip(parser)
            }
        }
        return if (points.isEmpty()) null else TrackSegment(points)
    }

    /** Reads a `<trkpt>`/`<rtept>`. Returns null - rather than throwing - if lat/lon are unusable. */
    private fun readPoint(parser: XmlPullParser): TrackPoint? {
        val latitude = parser.getAttributeValue(null, ATTR_LAT)?.toDoubleOrNull()
        val longitude = parser.getAttributeValue(null, ATTR_LON)?.toDoubleOrNull()

        var elevation: Double? = null
        var time: Instant? = null
        var accuracy: Double? = null

        forEachChild(parser) {
            when (parser.name) {
                TAG_ELE -> elevation = readText(parser).trim().toDoubleOrNull()
                TAG_TIME -> time = parseGpxTime(readText(parser))
                // Not a true dilution-of-precision figure - see TrackPoint.accuracyMeters -
                // but the closest slot GPX has, and the one this app's own writer uses.
                TAG_HDOP -> accuracy = readText(parser).trim().toDoubleOrNull()
                else -> skip(parser)
            }
        }

        if (latitude == null || longitude == null) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return TrackPoint(latitude, longitude, elevation, time, accuracy)
    }

    /**
     * Runs [body] once per child element, with the parser positioned on that child's
     * START_TAG. [body] must leave the parser on the matching END_TAG - which [readText],
     * [skip] and the nested `forEachChild` calls all do.
     */
    private inline fun forEachChild(parser: XmlPullParser, body: () -> Unit) {
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> body()
                XmlPullParser.END_TAG -> if (parser.depth <= depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Consumes the current element and everything inside it. */
    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Escape hatch for a segment that blows past [MAX_POINTS_PER_SEGMENT]: consumes the
     * remainder of the enclosing element (whose depth is [depth]) and stops, rather than
     * running to the end of the document and silently dropping every track after it.
     */
    private fun skipRest(parser: XmlPullParser, depth: Int) {
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (parser.depth <= depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readText(parser: XmlPullParser): String {
        var text = ""
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text
            parser.nextTag()
        }
        return text
    }

    companion object {
        /**
         * A hostile or corrupt file should not be able to OOM the process. Well past any
         * plausible real recording: at 1 Hz this is over 11 days of continuous logging.
         */
        private const val MAX_POINTS_PER_SEGMENT = 1_000_000

        private const val TAG_GPX = "gpx"
        private const val TAG_METADATA = "metadata"
        private const val TAG_TRK = "trk"
        private const val TAG_TRKSEG = "trkseg"
        private const val TAG_TRKPT = "trkpt"
        private const val TAG_RTE = "rte"
        private const val TAG_RTEPT = "rtept"
        private const val TAG_WPT = "wpt"
        private const val TAG_NAME = "name"
        private const val TAG_DESC = "desc"
        private const val TAG_ELE = "ele"
        private const val TAG_TIME = "time"
        private const val TAG_HDOP = "hdop"
        private const val ATTR_LAT = "lat"
        private const val ATTR_LON = "lon"

        private val DEFAULT_PULL_PARSER: () -> XmlPullParser = {
            XmlPullParserFactory.newInstance().newPullParser()
        }

        /**
         * GPX says ISO 8601 UTC, but exporters disagree: most write a `Z` suffix, some an
         * offset, a few neither. A timestamp matching none is dropped, downgrading the
         * track to untimed rather than failing the file.
         *
         * The spelling is inspected before a parser is chosen rather than trying all three:
         * this runs once per point, and try/catch meant a filled-in stack trace per point.
         */
        internal fun parseGpxTime(raw: String): Instant? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return try {
                when {
                    // `Z`, and only `Z`, is what Instant.parse accepts of the three.
                    text.endsWith('Z') || text.endsWith('z') -> Instant.parse(text)
                    hasOffset(text) -> OffsetDateTime.parse(text).toInstant()
                    else -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
                }
            } catch (_: DateTimeParseException) {
                null
            }
        }

        /**
         * Whether a timestamp carries a `+hh:mm` or `-hh:mm` zone.
         *
         * Looked for in the time part only: the date's own separators are hyphens, so
         * anything that simply searched for a minus sign would call every date an offset.
         */
        private fun hasOffset(text: String): Boolean {
            val timeStart = text.indexOf('T').takeIf { it >= 0 } ?: return false
            for (i in timeStart + 1 until text.length) {
                if (text[i] == '+' || text[i] == '-') return true
            }
            return false
        }
    }
}
