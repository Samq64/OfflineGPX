package dev.samuelq.gpx.data.gpx

import java.io.File

/**
 * Puts a new name into a GPX file's first `<trk>`, copying every other byte untouched.
 *
 * Reparsing and re-serialising through [GpxWriter] would be lossy: [GpxParser] drops
 * `<extensions>` (heart rate, cadence, power) and every `<trk>` after the first, which on
 * an imported file is the user's own data.
 *
 * Only the head is examined - a `<trk>`'s `<name>` precedes its first `<trkseg>` - so
 * nothing here scales with the length of the ride. [rewrite] reports failure and writes
 * nothing rather than guess at a file it doesn't recognise; the row's name is what the
 * screens and the export filename show either way.
 */
internal object GpxNameRewriter {

    /**
     * Writes [source] to [destination] with the first `<trk>`'s name set to [name], or
     * cleared when it is null.
     *
     * @return false if the file is not shaped in a way this can edit safely, in which case
     *   [destination] has not been written.
     */
    fun rewrite(source: File, destination: File, name: String?): Boolean {
        source.inputStream().buffered().use { input ->
            val head = ByteArray(HEAD_BYTES)
            var filled = 0
            while (filled < head.size) {
                val read = input.read(head, filled, head.size - filled)
                if (read < 0) break
                filled += read
            }

            if (!isSpliceable(head, filled)) return false
            // ISO-8859-1 round-trips arbitrary bytes one-for-one, so every byte the file
            // had survives exactly. The name goes in as UTF-8 bytes; see `escape`.
            val header = String(head, 0, filled, Charsets.ISO_8859_1)
            val spliced = splice(header, name) ?: return false

            destination.outputStream().buffered().use { output ->
                output.write(spliced.toByteArray(Charsets.ISO_8859_1))
                // Positioned just past the head, so this is the untouched remainder.
                input.copyTo(output)
            }
        }
        return true
    }

    /**
     * Whether splicing UTF-8 bytes into [bytes] leaves a valid document. It doesn't if the
     * file is in a wider encoding. XML defaults to UTF-8, so only a BOM or an explicit
     * declaration can say otherwise.
     */
    private fun isSpliceable(bytes: ByteArray, size: Int): Boolean {
        if (size == 0) return false
        if (size >= 2) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if ((first == 0xFF && second == 0xFE) || (first == 0xFE && second == 0xFF)) return false
        }
        val declaration = String(bytes, 0, minOf(size, DECLARATION_BYTES), Charsets.ISO_8859_1)
        val encoding = ENCODING.find(declaration)?.groupValues?.get(1) ?: return true
        return encoding.equals("UTF-8", ignoreCase = true) ||
            encoding.equals("US-ASCII", ignoreCase = true) ||
            encoding.equals("ASCII", ignoreCase = true)
    }

    /** The header with the name replaced, or null if the shape isn't one this recognises. */
    private fun splice(header: String, name: String?): String? {
        val track = TRK.find(header) ?: return null
        val openEnd = header.indexOf('>', track.range.last + 1)
        if (openEnd < 0) return null
        // <trk/> carries no points, so it is not a track anyone renamed.
        if (header[openEnd - 1] == '/') return null

        val bodyStart = openEnd + 1
        // A `<trk>`'s name comes before its points, and before any `<extensions>` - which
        // may well hold a `<name>` of their own that is not this track's.
        val limit = minOf(
            TRACK_BODY.find(header, bodyStart)?.range?.first ?: header.length,
            header.length,
        )

        val text = name?.let(::escape).orEmpty()
        val existing = NAME.find(header, bodyStart)?.takeIf { it.range.first < limit }

        if (existing == null) {
            if (name == null) return header
            // The document's own prefix, so the inserted tag lands in the namespace the
            // file is written in rather than an undeclared default one.
            val prefix = track.groupValues[1]
            return header.substring(0, bodyStart) +
                "<${prefix}name>$text</${prefix}name>" +
                header.substring(bodyStart)
        }

        val nameOpenEnd = header.indexOf('>', existing.range.last + 1)
        if (nameOpenEnd < 0) return null

        val prefix = existing.groupValues[1]
        // An empty `<name/>` has no text node to replace, so it is swapped out whole.
        if (header[nameOpenEnd - 1] == '/') {
            return header.substring(0, existing.range.first) +
                "<${prefix}name>$text</${prefix}name>" +
                header.substring(nameOpenEnd + 1)
        }

        val close = NAME_CLOSE.find(header, nameOpenEnd + 1)?.takeIf { it.range.first < limit }
            ?: return null
        return header.substring(0, nameOpenEnd + 1) + text + header.substring(close.range.first)
    }

    /**
     * XML-escaped, then re-read as bytes: the header is spliced as ISO-8859-1, so anything
     * inserted has to arrive already encoded as the document's UTF-8, one char per byte.
     */
    private fun escape(value: String): String {
        val escaped = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return String(escaped.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    }

    /** Well past any real `<trk>` header, and far short of a file worth streaming. */
    private const val HEAD_BYTES = 64 * 1024

    /** An XML declaration is the first thing in the file, or there isn't one. */
    private const val DECLARATION_BYTES = 256

    // Optional namespace prefixes throughout: GPX is written both with the schema as the
    // default namespace and under a prefix.
    private val TRK = Regex("""<([A-Za-z0-9_.-]+:)?trk(?=[\s/>])""")
    private val NAME = Regex("""<([A-Za-z0-9_.-]+:)?name(?=[\s/>])""")
    private val NAME_CLOSE = Regex("""</([A-Za-z0-9_.-]+:)?name\s*>""")
    private val TRACK_BODY =
        Regex("""<(?:[A-Za-z0-9_.-]+:)?(?:trkseg|trkpt|extensions)(?=[\s/>])|</(?:[A-Za-z0-9_.-]+:)?trk\s*>""")

    private val ENCODING = Regex("""<\?xml[^>]*\bencoding\s*=\s*["']([^"']+)["']""")
}
