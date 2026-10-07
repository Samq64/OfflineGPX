package dev.samuelq.gpx.data.gpx

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.core.model.isValidCoordinate
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streaming, tolerant GPX reader: anything unrecognised is skipped; only a non-GPX
 * document is an error.
 *
 * @param newPullParser injected for plain-JVM tests, where framework xmlpull is stubbed.
 */
class GpxParser(private val newPullParser: () -> XmlPullParser = DEFAULT_PULL_PARSER) {

    fun parse(input: InputStream): Track {
        val parser = try {
            newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                // FEATURE_PROCESS_DOCDECL stays false, blocking entity-expansion attacks.
                setInput(input, null) // null: encoding from the XML declaration
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
        val points = TrackPointsBuilder()
        val waypoints = mutableListOf<Waypoint>()
        var trackName: String? = null
        var metadataName: String? = null
        var trackDescription: String? = null
        var trackType: String? = null

        forEachChild(parser) {
            when (parser.name) {
                TAG_WPT -> readWaypoint(parser)?.let(waypoints::add)

                TAG_METADATA -> forEachChild(parser) {
                    when (parser.name) {
                        TAG_NAME -> metadataName = readLabel(parser)
                        else -> skip(parser)
                    }
                }

                TAG_TRK -> forEachChild(parser) {
                    when (parser.name) {
                        TAG_NAME -> {
                            val value = readLabel(parser)
                            // First named track wins; later ones are usually laps.
                            if (trackName == null) trackName = value
                        }

                        TAG_DESC -> {
                            val value = readLabel(parser)
                            if (trackDescription == null) trackDescription = value
                        }

                        TAG_TYPE -> {
                            val value = readLabel(parser)
                            if (trackType == null) trackType = value
                        }

                        TAG_TRKSEG -> readSegment(parser, points)
                        else -> skip(parser)
                    }
                }

                // Routes are untimed but still worth showing.
                TAG_RTE -> {
                    var routeName: String? = null
                    points.startSegment()
                    forEachChild(parser) {
                        when (parser.name) {
                            TAG_NAME -> routeName = readLabel(parser)
                            TAG_RTEPT -> readPoint(parser)?.let(points::add)
                            else -> skip(parser)
                        }
                    }
                    if (trackName == null) trackName = routeName
                }

                else -> skip(parser)
            }
        }

        return Track(
            name = trackName ?: metadataName,
            points = points.build(),
            description = trackDescription,
            type = trackType,
            waypoints = waypoints,
        )
    }

    /** Null if lat/lon are unusable. */
    private fun readWaypoint(parser: XmlPullParser): Waypoint? {
        val latitude = parser.getAttributeValue(null, ATTR_LAT)?.toDoubleOrNull()
        val longitude = parser.getAttributeValue(null, ATTR_LON)?.toDoubleOrNull()

        var elevation: Double? = null
        var time: Instant? = null
        var name: String? = null
        var description: String? = null
        var comment: String? = null

        forEachChild(parser) {
            when (parser.name) {
                TAG_ELE -> elevation = readText(parser).trim().toDoubleOrNull()
                TAG_TIME -> time = parseGpxTime(readText(parser))
                TAG_NAME -> name = readLabel(parser)
                TAG_DESC -> description = readLabel(parser)
                TAG_CMT -> comment = readLabel(parser)
                else -> skip(parser)
            }
        }

        if (latitude == null || longitude == null) return null
        if (!isValidCoordinate(latitude, longitude)) return null
        // Older files from this app put the label in <desc>.
        return Waypoint(TrackPoint(latitude, longitude, elevation, time), name ?: description ?: comment)
    }

    /** Appends one `<trkseg>` to [points] as a segment of its own. */
    private fun readSegment(parser: XmlPullParser, points: TrackPointsBuilder) {
        val depth = parser.depth
        var count = 0
        points.startSegment()
        forEachChild(parser) {
            when (parser.name) {
                TAG_TRKPT -> readPoint(parser)?.let {
                    if (count++ < MAX_POINTS_PER_SEGMENT) {
                        points.add(it)
                    } else {
                        // Non-local return, leaving the parser on this <trkseg>'s END_TAG.
                        skipRest(parser, depth)
                        return
                    }
                }

                else -> skip(parser)
            }
        }
    }

    /** Null if lat/lon are unusable. */
    private fun readPoint(parser: XmlPullParser): TrackPoint? {
        val latitude = parser.getAttributeValue(null, ATTR_LAT)?.toDoubleOrNull()
        val longitude = parser.getAttributeValue(null, ATTR_LON)?.toDoubleOrNull()

        var elevation: Double? = null
        var time: Instant? = null

        forEachChild(parser) {
            when (parser.name) {
                TAG_ELE -> elevation = readText(parser).trim().toDoubleOrNull()
                TAG_TIME -> time = parseGpxTime(readText(parser))
                else -> skip(parser)
            }
        }

        if (latitude == null || longitude == null) return null
        if (!isValidCoordinate(latitude, longitude)) return null
        return TrackPoint(latitude, longitude, elevation, time)
    }

    /** Runs [body] per child START_TAG; [body] must leave the parser on the matching END_TAG. */
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

    /** Consumes the rest of the element at [depth], so later tracks still get parsed. */
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

    /** Trimmed; null if blank. */
    private fun readLabel(parser: XmlPullParser): String? = readText(parser).trim().takeIf(String::isNotEmpty)

    companion object {
        /** OOM guard; over 11 days at 1 Hz. */
        internal const val MAX_POINTS_PER_SEGMENT = 1_000_000

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
        private const val TAG_TYPE = "type"
        private const val TAG_CMT = "cmt"
        private const val TAG_ELE = "ele"
        private const val TAG_TIME = "time"
        private const val ATTR_LAT = "lat"
        private const val ATTR_LON = "lon"

        private val DEFAULT_PULL_PARSER: () -> XmlPullParser = {
            XmlPullParserFactory.newInstance().newPullParser()
        }

        /**
         * Accepts `Z`, an offset, or no zone (UTC); null otherwise. Picks the parser up
         * front since a try-each approach costs an exception per point.
         */
        internal fun parseGpxTime(raw: String): Instant? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return try {
                when {
                    text.endsWith('Z') || text.endsWith('z') -> Instant.parse(text)
                    hasOffset(text) -> OffsetDateTime.parse(text).toInstant()
                    else -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
                }
            } catch (_: DateTimeParseException) {
                null
            }
        }

        /** Searches after the `T` only, since the date itself contains hyphens. */
        private fun hasOffset(text: String): Boolean {
            val timeStart = text.indexOf('T').takeIf { it >= 0 } ?: return false
            for (i in timeStart + 1 until text.length) {
                if (text[i] == '+' || text[i] == '-') return true
            }
            return false
        }
    }
}
