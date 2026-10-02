package dev.samuelq.gpx.data.gpx

import dev.samuelq.gpx.core.model.isValidCoordinate
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a GPX document keeping only some of its points and waypoints. Everything else passes
 * through: extensions, metadata, comments, other apps' data. [GpxWriter] would keep only what
 * the app reads.
 *
 * Points are counted as [GpxParser] reads them, so an index here is one in the parsed track.
 * Unreadable points and waypoints, which have no index, are dropped. A segment left with no
 * points goes too, as does `<metadata><bounds>`.
 */
class GpxTrimmer(
    private val newPullParser: () -> XmlPullParser = { XmlPullParserFactory.newInstance().newPullParser() },
    private val newSerializer: () -> XmlSerializer = { XmlPullParserFactory.newInstance().newSerializer() },
) {

    fun trim(
        input: InputStream,
        output: OutputStream,
        keepPoint: (index: Int) -> Boolean,
        keepWaypoint: (index: Int) -> Boolean,
    ) {
        val parser = newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
        Copy(parser, newSerializer().apply { setOutput(output, ENCODING) }, keepPoint, keepWaypoint).run()
    }

    /** A start tag held back until something inside it is kept. */
    private class StartTag(
        val namespaces: List<Pair<String, String>>,
        val namespace: String?,
        val name: String,
        val attributes: List<Triple<String?, String, String>>,
        val leadingWhitespace: String,
    )

    private class Copy(
        private val parser: XmlPullParser,
        private val xml: XmlSerializer,
        private val keepPoint: (Int) -> Boolean,
        private val keepWaypoint: (Int) -> Boolean,
    ) {
        private val path = ArrayList<String>()
        private var skipDepth = 0
        /** Whitespace since the last write, dropped with an element it led up to. */
        private val whitespace = StringBuilder()
        /** The open `<trkseg>`, until its first kept point. */
        private var pendingSegment: StartTag? = null
        private var pointIndex = 0
        private var segmentPoints = 0
        private var waypointIndex = 0

        fun run() {
            xml.startDocument(ENCODING, null)
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.START_TAG -> startTag()
                    XmlPullParser.END_TAG -> endTag()
                    XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> text(parser.text)
                    XmlPullParser.CDSECT -> passThrough { xml.cdsect(parser.text) }
                    XmlPullParser.ENTITY_REF -> parser.text?.let { value -> passThrough { xml.text(value) } }
                    XmlPullParser.COMMENT -> passThrough { xml.comment(parser.text) }
                    XmlPullParser.PROCESSING_INSTRUCTION -> passThrough { xml.processingInstruction(parser.text) }
                    // No DTD: the parser never expands one, and it's no part of GPX.
                    XmlPullParser.DOCDECL -> Unit
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            xml.endDocument()
            xml.flush()
        }

        private fun startTag() {
            if (skipDepth > 0) {
                skipDepth++
                return
            }
            val name = parser.name
            val keep = when {
                name == TAG_TRKPT && path == TRKSEG_PATH || name == TAG_RTEPT && path == RTE_PATH -> point()
                name == TAG_WPT && path == ROOT_PATH -> readable() && keepWaypoint(waypointIndex++)
                // Wider than what's left; optional, so dropped rather than recomputed ahead of the points.
                name == TAG_BOUNDS && path == METADATA_PATH -> false
                // Nothing but points is worth reopening an emptied segment for.
                pendingSegment != null -> false
                else -> true
            }
            if (!keep) {
                whitespace.clear()
                skipDepth = 1
                return
            }
            if (name == TAG_TRKSEG && path == TRK_PATH) {
                segmentPoints = 0
                pendingSegment = capture()
                path += name
                return
            }
            pendingSegment?.let { segment ->
                pendingSegment = null
                writeStart(segment)
            }
            writeStart(capture())
            path += name
        }

        private fun point(): Boolean {
            if (!readable()) return false
            // Only a track segment is capped, as in the parser.
            if (path == TRKSEG_PATH && segmentPoints++ >= GpxParser.MAX_POINTS_PER_SEGMENT) return false
            return keepPoint(pointIndex++)
        }

        /** Mirrors the parser's test, so the indices agree. */
        private fun readable(): Boolean {
            val latitude = parser.getAttributeValue(null, ATTR_LAT)?.toDoubleOrNull() ?: return false
            val longitude = parser.getAttributeValue(null, ATTR_LON)?.toDoubleOrNull() ?: return false
            return isValidCoordinate(latitude, longitude)
        }

        private fun endTag() {
            if (skipDepth > 0) {
                skipDepth--
                return
            }
            path.removeAt(path.lastIndex)
            if (parser.name == TAG_TRKSEG && pendingSegment != null) {
                pendingSegment = null
                whitespace.clear()
                return
            }
            flushWhitespace()
            xml.endTag(parser.namespace.ifEmpty { null }, parser.name)
        }

        private fun text(value: String) {
            if (skipDepth > 0) return
            // Before the root, a serializer won't take text, and it's only whitespace there.
            if (path.isEmpty()) return
            if (value.isBlank()) {
                whitespace.append(value)
            } else if (pendingSegment == null) {
                flushWhitespace()
                xml.text(value)
            }
        }

        private inline fun passThrough(write: () -> Unit) {
            if (skipDepth > 0 || pendingSegment != null) return
            flushWhitespace()
            write()
        }

        private fun capture(): StartTag {
            val depth = parser.depth
            val namespaces = (parser.getNamespaceCount(depth - 1) until parser.getNamespaceCount(depth)).map {
                (parser.getNamespacePrefix(it) ?: "") to parser.getNamespaceUri(it)
            }
            val attributes = (0 until parser.attributeCount).map {
                Triple(parser.getAttributeNamespace(it).ifEmpty { null }, parser.getAttributeName(it), parser.getAttributeValue(it))
            }
            val leading = whitespace.toString().also { whitespace.clear() }
            return StartTag(namespaces, parser.namespace.ifEmpty { null }, parser.name, attributes, leading)
        }

        private fun writeStart(tag: StartTag) {
            // Its own; anything buffered since belongs inside it, as with a held segment's first point.
            if (tag.leadingWhitespace.isNotEmpty()) xml.text(tag.leadingWhitespace)
            tag.namespaces.forEach { (prefix, uri) -> xml.setPrefix(prefix, uri) }
            xml.startTag(tag.namespace, tag.name)
            tag.attributes.forEach { (namespace, name, value) -> xml.attribute(namespace, name, value) }
        }

        private fun flushWhitespace() {
            if (whitespace.isEmpty()) return
            xml.text(whitespace.toString())
            whitespace.clear()
        }
    }

    private companion object {
        const val ENCODING = "UTF-8"
        const val TAG_TRK = "trk"
        const val TAG_TRKSEG = "trkseg"
        const val TAG_TRKPT = "trkpt"
        const val TAG_RTE = "rte"
        const val TAG_RTEPT = "rtept"
        const val TAG_WPT = "wpt"
        const val TAG_BOUNDS = "bounds"
        const val ATTR_LAT = "lat"
        const val ATTR_LON = "lon"

        val ROOT_PATH = listOf("gpx")
        val METADATA_PATH = listOf("gpx", "metadata")
        val TRK_PATH = listOf("gpx", TAG_TRK)
        val TRKSEG_PATH = listOf("gpx", TAG_TRK, TAG_TRKSEG)
        val RTE_PATH = listOf("gpx", TAG_RTE)
    }
}
