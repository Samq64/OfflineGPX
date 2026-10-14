package dev.samuelq.gpx.data.gpx

import java.io.InputStream
import java.io.OutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer

/**
 * Copies a GPX document, optionally renaming its first track or setting its type or colour.
 * Everything else passes through: extensions, metadata, comments, other apps' data.
 */
class GpxRelabeller(
    private val newPullParser: () -> XmlPullParser = { XmlPullParserFactory.newInstance().newPullParser() },
    private val newSerializer: () -> XmlSerializer = { XmlPullParserFactory.newInstance().newSerializer() },
) {

    /**
     * @param name replaces the first `<trk>`'s name, or is added as its first child; blank
     *   removes it, null leaves it.
     * @param type the same for the first `<trk>`'s type, added where the schema orders it.
     * @param color a Garmin `DisplayColor` name for the first `<trk>`. Any colour extension
     *   already there goes, so none contradicts it; null leaves them.
     */
    fun relabel(
        input: InputStream,
        output: OutputStream,
        name: String? = null,
        type: String? = null,
        color: String? = null,
    ) {
        val parser = newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
        Copy(
            parser,
            newSerializer().apply {
                setOutput(output, ENCODING)
            },
            name,
            type,
            color,
        )
            .run()
    }

    /** A start tag as read, to write in place of a replaced one. */
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
        /** Cleared once the first track has it. */
        private var newName: String?,
        /** Likewise. */
        private var newType: String?,
        /** Likewise. */
        private var newColor: String?,
    ) {
        private val path = ArrayList<String>()
        private var skipDepth = 0

        /** Whitespace since the last write, dropped with an element it led up to. */
        private val whitespace = StringBuilder()

        /** Inside the first `<trk>`, before its first child. */
        private var nameDue = false

        /** Inside the first `<trk>`, before what follows its type. */
        private var typeDue = false
        private var trackNamespace: String? = null

        /** Inside the first `<trk>`, dropping its colour extensions. */
        private var recolouring = false

        /** Inside the first `<trk>`, before [newColor] is written. */
        private var colorDue = false

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
                    // Never stored: the parser refuses a file with one.
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
            if (nameDue && path == TRK_PATH) {
                nameDue = false
                if (name == TAG_NAME) {
                    // Replaced whole, so its old text and children go.
                    writeName(capture())
                    skipDepth = 1
                    return
                }
                insertName()
            }
            if (typeDue && path == TRK_PATH) {
                if (name == TAG_TYPE) {
                    typeDue = false
                    writeType(capture())
                    skipDepth = 1
                    return
                }
                if (name == TAG_EXTENSIONS || name == TAG_TRKSEG) insertType()
            }
            if (colorDue && path == TRK_PATH && name == TAG_TRKSEG) insert { writeColor(wrapped = true) }
            if (recolouring && path == TRK_EXTENSIONS_PATH && GpxColors.isColorElement(parser.namespace, name)) {
                whitespace.clear()
                skipDepth = 1
                return
            }
            writeStart(capture())
            path += name
            if (name == TAG_TRK && path == TRK_PATH) {
                nameDue = newName != null
                typeDue = newType != null
                recolouring = newColor != null
                colorDue = recolouring
                trackNamespace = parser.namespace.ifEmpty { null }
            }
        }

        private fun insertName() {
            nameDue = false
            insert { writeName() }
        }

        private fun insertType() {
            typeDue = false
            insert { writeType() }
        }

        /** Indented as the child or end tag it goes before. */
        private inline fun insert(write: () -> Unit) {
            val indent = whitespace.toString()
            flushWhitespace()
            write()
            whitespace.append(indent)
        }

        private fun writeName(replaced: StartTag? = null) {
            writeLabel(TAG_NAME, newName, replaced)
            newName = null
        }

        private fun writeType(replaced: StartTag? = null) {
            writeLabel(TAG_TYPE, newType, replaced)
            newType = null
        }

        /** [value] in place of [replaced], or new under the track's namespace; blank writes nothing. */
        private fun writeLabel(tag: String, value: String?, replaced: StartTag?) {
            val text = value?.trim()?.xmlSafe()
            if (text.isNullOrEmpty()) return
            val namespace = replaced?.namespace ?: trackNamespace
            if (replaced != null) writeStart(replaced) else xml.startTag(namespace, tag)
            xml.text(text).endTag(namespace, tag)
        }

        /** [newColor] as Garmin's extension, in an `<extensions>` of the track's own if [wrapped]. */
        private fun writeColor(wrapped: Boolean) {
            val color = newColor ?: return
            colorDue = false
            newColor = null
            if (wrapped) xml.startTag(trackNamespace, TAG_EXTENSIONS)
            xml.setPrefix(GARMIN_PREFIX, GpxColors.GARMIN_NAMESPACE)
            xml.startTag(GpxColors.GARMIN_NAMESPACE, GpxColors.GARMIN_TRACK)
            xml.startTag(GpxColors.GARMIN_NAMESPACE, GpxColors.GARMIN_COLOR)
                .text(color)
                .endTag(GpxColors.GARMIN_NAMESPACE, GpxColors.GARMIN_COLOR)
            xml.endTag(GpxColors.GARMIN_NAMESPACE, GpxColors.GARMIN_TRACK)
            if (wrapped) xml.endTag(trackNamespace, TAG_EXTENSIONS)
        }

        private fun endTag() {
            if (skipDepth > 0) {
                skipDepth--
                return
            }
            if (colorDue && path == TRK_EXTENSIONS_PATH) insert { writeColor(wrapped = false) }
            if (path == TRK_PATH) {
                if (nameDue) insertName()
                if (typeDue) insertType()
                if (colorDue) insert { writeColor(wrapped = true) }
                recolouring = false
            }
            path.removeAt(path.lastIndex)
            flushWhitespace()
            xml.endTag(parser.namespace.ifEmpty { null }, parser.name)
        }

        private fun text(value: String) {
            if (skipDepth > 0) return
            // Before the root, a serializer won't take text, and it's only whitespace there.
            if (path.isEmpty()) return
            if (value.isBlank()) {
                whitespace.append(value)
            } else {
                flushWhitespace()
                xml.text(value)
            }
        }

        private inline fun passThrough(write: () -> Unit) {
            if (skipDepth > 0) return
            flushWhitespace()
            write()
        }

        private fun capture(): StartTag {
            val depth = parser.depth
            val namespaces = (parser.getNamespaceCount(depth - 1) until parser.getNamespaceCount(depth)).map {
                (parser.getNamespacePrefix(it) ?: "") to parser.getNamespaceUri(it)
            }
            val attributes = (0 until parser.attributeCount).map {
                Triple(
                    parser.getAttributeNamespace(it).ifEmpty {
                        null
                    },
                    parser.getAttributeName(it),
                    parser.getAttributeValue(it),
                )
            }
            val leading = whitespace.toString().also { whitespace.clear() }
            return StartTag(namespaces, parser.namespace.ifEmpty { null }, parser.name, attributes, leading)
        }

        private fun writeStart(tag: StartTag) {
            // Its own; anything buffered since belongs inside it.
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
        const val TAG_NAME = "name"
        const val TAG_TYPE = "type"
        const val TAG_EXTENSIONS = "extensions"
        const val GARMIN_PREFIX = "gpxx"

        val TRK_PATH = listOf("gpx", TAG_TRK)
        val TRK_EXTENSIONS_PATH = listOf("gpx", TAG_TRK, TAG_EXTENSIONS)
    }
}
