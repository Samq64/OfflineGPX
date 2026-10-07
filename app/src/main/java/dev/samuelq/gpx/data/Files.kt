package dev.samuelq.gpx.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal fun ContentResolver.displayName(uri: Uri): String? = try {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
    }
} catch (e: Exception) {
    Log.d("Files", "Could not query a display name for $uri", e)
    null
}

/** Its size in bytes, if the provider says. */
internal fun ContentResolver.size(uri: Uri): Long? = try {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
} catch (e: Exception) {
    Log.d("Files", "Could not query a size for $uri", e)
    null
}

/** Copies [uri] into [destination]. False when no provider could open it. */
internal fun ContentResolver.copyInto(uri: Uri, destination: File): Boolean = openInputStream(uri)?.use { input ->
    destination.outputStream().use { output -> input.copyTo(output) }
} != null

/** A sanitised, numbered-if-taken file in [dir]; never overwrites. */
internal fun uniqueFile(dir: File, name: String?, extension: String, fallback: String): File =
    File(dir, uniqueName(name, extension, fallback) { File(dir, it).exists() })

/**
 * [name] sanitised, with [extension], numbered before the extension while [taken]. Ours, not
 * a provider's: some number after it, turning `a.gpx` into `a.gpx (1)`.
 */
internal fun uniqueName(name: String?, extension: String, fallback: String, taken: (String) -> Boolean): String {
    // A slash is replaced, not cut at: a track called "Mon/Tue ride" keeps both halves.
    val base = safeFileName(name.orEmpty())
        .let { if (it.endsWith(".$extension", ignoreCase = true)) it.dropLast(extension.length + 1) else it }
        .take(MAX_FILENAME_LENGTH)
        .ifBlank { fallback }

    var candidate = "$base.$extension"
    var suffix = 2
    while (taken(candidate)) {
        candidate = "$base ($suffix).$extension"
        suffix++
    }
    return candidate
}

/** Only what Android, FAT or a SAF provider refuses is replaced; `&` and `'` are kept. */
internal fun safeFileName(name: String): String = name.replace(UNSAFE_FILENAME_CHARACTERS, "_")

private const val MAX_FILENAME_LENGTH = 80
private val UNSAFE_FILENAME_CHARACTERS = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")

/** Replaces [file] only once [write] has finished and its bytes are synced, so a crash leaves the old one. */
internal inline fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.buffered().let {
            write(it)
            it.flush()
        }
    } catch (e: Throwable) {
        atomic.failWrite(stream)
        throw e
    }
    atomic.finishWrite(stream)
}

/** Byte for byte, streamed; lengths first, so a mismatch is usually a stat. */
internal fun sameBytes(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    a.inputStream().use { x ->
        b.inputStream().use { y ->
            val bufferX = ByteArray(CHUNK_BYTES)
            val bufferY = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = x.readChunk(bufferX)
                if (read != y.readChunk(bufferY)) return false
                if (read == 0) return true
                // Past a short read, both still hold the previous chunk, which matched.
                if (!bufferX.contentEquals(bufferY)) return false
            }
        }
    }
}

/** Fills [buffer] unless the stream ends first; the bytes read. */
private fun InputStream.readChunk(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

private const val CHUNK_BYTES = 8 * 1024
