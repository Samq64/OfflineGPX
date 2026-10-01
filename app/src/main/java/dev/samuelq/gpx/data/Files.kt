package dev.samuelq.gpx.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

internal fun ContentResolver.displayName(uri: Uri): String? = try {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
    }
} catch (e: Exception) {
    Log.d("Files", "Could not query a display name for $uri", e)
    null
}

/** Copies [uri] into [destination]. False when no provider could open it. */
internal fun ContentResolver.copyInto(uri: Uri, destination: File): Boolean =
    openInputStream(uri)?.use { input ->
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
    val base = name.orEmpty()
        .let { if (it.endsWith(".$extension", ignoreCase = true)) it.dropLast(extension.length + 1) else it }
        .replace(UNSAFE_FILENAME_CHARACTERS, "_")
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

private const val MAX_FILENAME_LENGTH = 80
private val UNSAFE_FILENAME_CHARACTERS = Regex("""[\\/:*?"<>|]""")

/** Byte for byte, streamed; lengths first, so a mismatch is usually a stat. */
internal fun sameBytes(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    a.inputStream().buffered().use { x ->
        b.inputStream().buffered().use { y ->
            while (true) {
                val byte = x.read()
                if (byte != y.read()) return false
                if (byte == -1) return true
            }
        }
    }
}
