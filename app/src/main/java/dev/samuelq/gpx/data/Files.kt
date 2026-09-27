package dev.samuelq.gpx.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/** The name the provider gives a document, or null when it declines to say. */
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

/**
 * A file in [dir] not already taken, named after [name] where possible: a second
 * `ottawa.map` is a different area, and overwriting the first is the wrong answer.
 */
internal fun uniqueFile(dir: File, name: String?, extension: String, fallback: String): File {
    val base = name.orEmpty()
        .substringAfterLast('/')
        .let { if (it.endsWith(".$extension", ignoreCase = true)) it.dropLast(extension.length + 1) else it }
        .replace(UNSAFE_FILENAME_CHARACTERS, "_")
        .take(MAX_FILENAME_LENGTH)
        .ifBlank { fallback }

    var candidate = File(dir, "$base.$extension")
    var suffix = 2
    while (candidate.exists()) {
        candidate = File(dir, "$base ($suffix).$extension")
        suffix++
    }
    return candidate
}

private const val MAX_FILENAME_LENGTH = 80
private val UNSAFE_FILENAME_CHARACTERS = Regex("""[\\/:*?"<>|]""")
