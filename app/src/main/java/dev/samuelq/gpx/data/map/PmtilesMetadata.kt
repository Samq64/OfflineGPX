package dev.samuelq.gpx.data.map

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * The one field this app reads out of an archive's own JSON metadata block.
 *
 * The app ships no maps and fetches nothing, so it has no idea where an imported archive's
 * data came from. `attribution` is the mbtiles/tippecanoe convention every mainstream
 * pmtiles builder already writes into this block, so reading it back is the only honest way
 * to credit whatever source it actually is.
 */
object PmtilesMetadata {

    /**
     * The archive's declared source, or null if it does not say - which is also the honest
     * answer for a file some tool wrote without one.
     */
    fun readAttribution(file: File, header: PmtilesHeader): String? {
        val length = header.jsonMetadataLength
        if (length <= 0 || length > MAX_METADATA_BYTES) return null

        val json = try {
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(header.jsonMetadataOffset)
                val raw = ByteArray(length.toInt())
                handle.readFully(raw)
                val bytes = if (header.internalCompression == COMPRESSION_GZIP) {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                } else {
                    raw
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        }

        return parseAttribution(json)
    }

    /** Split out from [readAttribution] so the JSON handling can be tested without a file. */
    internal fun parseAttribution(json: String): String? = try {
        val attribution = (Json.parseToJsonElement(json) as? JsonObject)
            ?.get(FIELD_ATTRIBUTION)
            ?.jsonPrimitive
            ?.content
        attribution?.let(::stripHtml)?.trim()?.takeIf(String::isNotEmpty)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalStateException) {
        // Thrown by `jsonPrimitive` when the field is present but is an object or array
        // rather than a string - not the shape the convention expects, so not usable.
        null
    }

    /**
     * The mbtiles/tippecanoe convention is an HTML fragment, not plain text, and this app
     * only has a `Text` to put it in - so both markup and entities need stripping here.
     */
    private fun stripHtml(text: String): String = decodeEntities(text.replace(HTML_TAG, ""))

    /**
     * The handful of named entities an attribution line could plausibly contain, plus
     * numeric references - not a general HTML decoder, since nothing here needs one.
     */
    private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value

            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value

            else -> NAMED_ENTITIES[body] ?: match.value
        }
    }

    private fun codePointToString(codePoint: Int): String? =
        if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null

    private const val FIELD_ATTRIBUTION = "attribution"
    private const val COMPRESSION_GZIP = 2

    /** An attribution line is a sentence, not a payload; anything past this is not one. */
    private const val MAX_METADATA_BYTES = 1L * 1024 * 1024

    private val HTML_TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "copy" to "©",
        "reg" to "®",
        "nbsp" to " ",
    )
}
