package dev.samuelq.gpx.data.map

import android.content.Context
import android.net.Uri
import dev.samuelq.gpx.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** An offline basemap the user has imported, and what its header says about it. */
class OfflineMap(
    val file: File,
    val header: PmtilesHeader,
    val sizeBytes: Long,
    /**
     * The outline of what this archive actually contains, which is not its bounding box
     * unless it was cut from one. Empty when it could not be determined.
     */
    val coverage: Coverage = Coverage.None,
    /** What the archive itself says its data came from, or null if it does not say. */
    val attribution: String? = null,
) {
    /** The filename without its extension, which is whatever the user named the download. */
    val displayName: String get() = file.nameWithoutExtension

    /**
     * True if this and [other] cover any of the same ground. Compared as bounding boxes,
     * not true coverage - deliberately conservative, so a false "overlaps" costs a refusal
     * rather than two maps drawn on top of each other.
     */
    fun overlaps(other: OfflineMap): Boolean =
        header.minLongitude < other.header.maxLongitude &&
            other.header.minLongitude < header.maxLongitude &&
            header.minLatitude < other.header.maxLatitude &&
            other.header.minLatitude < header.maxLatitude
}

/** Why an import did not produce a map. */
enum class MapImportError {
    /** The file could not be read through the content resolver at all. */
    UNREADABLE,

    /** It was read, and it is not a PMTiles v3 archive. */
    NOT_AN_ARCHIVE,

    /** There is not enough free space to copy it. */
    NO_SPACE,

    /** It covers ground a map already shown covers, and two renderings of it cannot stack. */
    OVERLAPS,
}

sealed interface MapImportResult {
    class Imported(val map: OfflineMap) : MapImportResult
    class Failed(val error: MapImportError) : MapImportResult
}

/**
 * The offline maps on this device.
 *
 * Copied into app-private storage rather than read where they sit: PMTiles is a byte-range
 * format the renderer seeks around in constantly, and a SAF document is a stream through
 * another process with no promise of still being there tomorrow. MapLibre's
 * `pmtiles://file://` needs a real path anyway.
 *
 * Nothing here fetches anything - maps arrive through the file picker or not at all.
 */
class MapStore(
    context: Context,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext

    /** Private to the app, so no storage permission is involved on either side. */
    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }

    private val _maps = MutableStateFlow<List<OfflineMap>>(emptyList())

    /** Every imported map, newest first. Refreshed by [refresh] and by every mutation. */
    val maps: StateFlow<List<OfflineMap>> = _maps.asStateFlow()

    /**
     * The maps the renderer should draw. Resolved from stored filenames, not held as
     * objects, since the selection lives in settings and a file can vanish out from under it.
     */
    val active: Flow<List<OfflineMap>> =
        combine(_maps, settings.settings) { maps, current ->
            maps.filter { it.file.name in current.activeMapFiles }
        }

    /** Re-reads the directory. Cheap - a handful of files, each parsed to 127 bytes. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .mapNotNull { file ->
                PmtilesHeader.read(file)?.let { header ->
                    OfflineMap(
                        file = file,
                        header = header,
                        sizeBytes = file.length(),
                        coverage = PmtilesCoverage.read(file, header),
                        attribution = PmtilesMetadata.readAttribution(file, header),
                    )
                }
            }
            .sortedByDescending { it.file.lastModified() }

        _maps.value = found

        // A selection pointing at a file that is no longer there is worse than none: the
        // map screen would report a basemap it cannot draw.
        val names = found.mapTo(HashSet()) { it.file.name }
        val selected = settings.settings.value.activeMapFiles
        val surviving = selected.filterTo(HashSet()) { it in names }
        if (surviving.size != selected.size) settings.setActiveMapFiles(surviving)
    }

    /**
     * Copies the document at [uri] into private storage and reads its header, deleting the
     * copy again if it fails validation. Validated after the copy, not before - SAF makes
     * no promise a second open returns the same bytes.
     */
    suspend fun import(uri: Uri, suggestedName: String?): MapImportResult =
        withContext(Dispatchers.IO) {
            val destination = File(directory, uniqueName(suggestedName))
            try {
                val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                if (copied == null) {
                    destination.delete()
                    return@withContext MapImportResult.Failed(MapImportError.UNREADABLE)
                }
            } catch (_: IOException) {
                destination.delete()
                // Out of space is the one IO failure worth naming separately: it is the
                // user's to fix, and "couldn't read it" would send them looking in the
                // wrong place for a 90 MB file that did not fit.
                val error = if (appContext.filesDir.usableSpace < LOW_SPACE_BYTES) {
                    MapImportError.NO_SPACE
                } else {
                    MapImportError.UNREADABLE
                }
                return@withContext MapImportResult.Failed(error)
            } catch (_: SecurityException) {
                destination.delete()
                return@withContext MapImportResult.Failed(MapImportError.UNREADABLE)
            }

            val header = PmtilesHeader.read(destination)
            if (header == null) {
                destination.delete()
                return@withContext MapImportResult.Failed(MapImportError.NOT_AN_ARCHIVE)
            }

            val map = OfflineMap(
                file = destination,
                header = header,
                sizeBytes = destination.length(),
                coverage = PmtilesCoverage.read(destination, header),
                attribution = PmtilesMetadata.readAttribution(destination, header),
            )

            // Rejected outright rather than imported and left off: there's no toggle to
            // switch it on later, so it would just be a copy that can never be drawn.
            val activeMaps = _maps.value.filter { it.file.name in settings.settings.value.activeMapFiles }
            if (activeMaps.any { it.overlaps(map) }) {
                destination.delete()
                return@withContext MapImportResult.Failed(MapImportError.OVERLAPS)
            }

            refresh()
            settings.setActiveMapFiles(settings.settings.value.activeMapFiles + destination.name)
            MapImportResult.Imported(map)
        }

    suspend fun delete(map: OfflineMap) = withContext(Dispatchers.IO) {
        map.file.delete()
        // refresh() clears the selection if this was it.
        refresh()
    }

    /**
     * A name that is not already taken, keeping the user's if it is free.
     *
     * Two maps called `ottawa.pmtiles` are two different areas someone downloaded a month
     * apart, and silently overwriting the first is the wrong answer to a name collision.
     */
    private fun uniqueName(suggested: String?): String {
        val base = (suggested ?: DEFAULT_NAME)
            .substringAfterLast('/')
            .removeSuffix(".$EXTENSION")
            .replace(UNSAFE_CHARACTERS, "_")
            .take(MAX_NAME_LENGTH)
            .ifBlank { DEFAULT_NAME }

        var candidate = "$base.$EXTENSION"
        var suffix = 2
        while (File(directory, candidate).exists()) {
            candidate = "$base ($suffix).$EXTENSION"
            suffix++
        }
        return candidate
    }

    private companion object {
        const val DIRECTORY = "maps"
        const val EXTENSION = "pmtiles"
        const val DEFAULT_NAME = "map"
        const val MAX_NAME_LENGTH = 80

        /** Below this, a failed copy is read as the disk being full rather than broken. */
        const val LOW_SPACE_BYTES = 64L * 1024 * 1024

        val UNSAFE_CHARACTERS = Regex("""[\\/:*?"<>|]""")
    }
}
