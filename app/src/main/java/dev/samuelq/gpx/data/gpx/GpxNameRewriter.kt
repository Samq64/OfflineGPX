package dev.samuelq.gpx.data.gpx

import java.io.File

/**
 * Splices a new name into a GPX file's first `<trk>`, copying every other byte untouched.
 * Reparsing through [GpxParser] would lose `<extensions>` and later `<trk>`s.
 */
internal object GpxNameRewriter {

    /**
     * Copies [source] to [destination] with the first `<trk>`'s name set to [name] (null clears).
     *
     * @return false, with nothing written, if the file isn't a shape this can edit safely.
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
            // ISO-8859-1 round-trips arbitrary bytes one-for-one.
            val header = String(head, 0, filled, Charsets.ISO_8859_1)
            val spliced = splice(header, name) ?: return false

            destination.outputStream().buffered().use { output ->
                output.write(spliced.toByteArray(Charsets.ISO_8859_1))
                input.copyTo(output)
            }
        }
        return true
    }

    /** Whether the file is UTF-8 compatible, judged by BOM and XML declaration. */
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

    /** Null if the shape isn't recognised. */
    private fun splice(header: String, name: String?): String? {
        val track = TRK.find(header) ?: return null
        val openEnd = header.indexOf('>', track.range.last + 1)
        if (openEnd < 0) return null
        if (header[openEnd - 1] == '/') return null

        val bodyStart = openEnd + 1
        // Stop before `<extensions>`, which may hold a `<name>` of its own.
        val limit = minOf(
            TRACK_BODY.find(header, bodyStart)?.range?.first ?: header.length,
            header.length,
        )

        val text = name?.let(::escape).orEmpty()
        val existing = NAME.find(header, bodyStart)?.takeIf { it.range.first < limit }

        if (existing == null) {
            if (name == null) return header
            // Reuse the document's namespace prefix.
            val prefix = track.groupValues[1]
            return header.substring(0, bodyStart) +
                "<${prefix}name>$text</${prefix}name>" +
                header.substring(bodyStart)
        }

        val nameOpenEnd = header.indexOf('>', existing.range.last + 1)
        if (nameOpenEnd < 0) return null

        val prefix = existing.groupValues[1]
        // `<name/>` is swapped out whole.
        if (header[nameOpenEnd - 1] == '/') {
            return header.substring(0, existing.range.first) +
                "<${prefix}name>$text</${prefix}name>" +
                header.substring(nameOpenEnd + 1)
        }

        val close = NAME_CLOSE.find(header, nameOpenEnd + 1)?.takeIf { it.range.first < limit }
            ?: return null
        return header.substring(0, nameOpenEnd + 1) + text + header.substring(close.range.first)
    }

    /** XML-escaped and UTF-8 encoded, one char per byte, to match the ISO-8859-1 header. */
    private fun escape(value: String): String {
        val escaped = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return String(escaped.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    }

    /** Well past any real `<trk>` header. */
    private const val HEAD_BYTES = 64 * 1024

    private const val DECLARATION_BYTES = 256

    // GPX appears both unprefixed and under a namespace prefix.
    private val TRK = Regex("""<([A-Za-z0-9_.-]+:)?trk(?=[\s/>])""")
    private val NAME = Regex("""<([A-Za-z0-9_.-]+:)?name(?=[\s/>])""")
    private val NAME_CLOSE = Regex("""</([A-Za-z0-9_.-]+:)?name\s*>""")
    private val TRACK_BODY =
        Regex("""<(?:[A-Za-z0-9_.-]+:)?(?:trkseg|trkpt|extensions)(?=[\s/>])|</(?:[A-Za-z0-9_.-]+:)?trk\s*>""")

    private val ENCODING = Regex("""<\?xml[^>]*\bencoding\s*=\s*["']([^"']+)["']""")
}
