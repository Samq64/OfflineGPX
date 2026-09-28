package dev.samuelq.gpx.data.map

import android.content.Context
import android.net.Uri
import dev.samuelq.gpx.data.copyInto
import dev.samuelq.gpx.data.displayName
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.uniqueFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** An offline basemap the user has imported, and what its header says about it. */
class OfflineMap(
    val file: File,
    val header: MapFileHeader,
    val sizeBytes: Long,
) {
    val attribution: String? get() = header.attribution

    val displayName: String get() = file.nameWithoutExtension

    /** Bounding-box overlap; conservative, as a false positive only supersedes a map. */
    fun overlaps(other: OfflineMap): Boolean =
        header.minLongitude < other.header.maxLongitude &&
            other.header.minLongitude < header.maxLongitude &&
            header.minLatitude < other.header.maxLatitude &&
            other.header.minLatitude < header.maxLatitude
}

enum class MapImportError {
    UNREADABLE,

    /** Not a mapsforge map file. */
    NOT_A_MAP_FILE,

    NO_SPACE,
}

sealed interface MapImportResult {
    class Imported(val map: OfflineMap) : MapImportResult
    class Failed(val error: MapImportError) : MapImportResult
}

/** Offline maps, copied into app-private storage since VTM's reader needs a seekable real path. */
class MapStore(
    context: Context,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext

    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }

    private val _maps = MutableStateFlow<List<OfflineMap>>(emptyList())

    /** Newest first. */
    val maps: StateFlow<List<OfflineMap>> = _maps.asStateFlow()

    /** The maps the renderer should draw, per the filenames selected in settings. */
    val active: Flow<List<OfflineMap>> =
        combine(_maps, settings.settings) { maps, current ->
            maps.filter { it.file.name in current.activeMapFiles }
        }

    /** Filenames deleted but still undoable; in memory, like pending track deletes. */
    private val pendingDelete = ConcurrentHashMap.newKeySet<String>()

    /** Deletes after an undo lapses finish even if Settings has closed. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Parsed headers, keyed by path and mtime so unchanged files aren't re-read each launch. */
    private val readMaps = ConcurrentHashMap<Key, OfflineMap>()

    private data class Key(val path: String, val modifiedAt: Long)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }

        val keys = files.associateBy { Key(it.path, it.lastModified()) }
        val found = keys
            .mapNotNull { (key, file) -> readMaps[key] ?: read(file)?.also { readMaps[key] = it } }
            .sortedByDescending { it.file.lastModified() }

        readMaps.keys.retainAll(keys.keys)

        _maps.value = found.filter { it.file.name !in pendingDelete }

        // Drop selections of files that are gone. Not pending ones, so an undo restores them.
        val names = found.mapTo(HashSet()) { it.file.name }
        val selected = settings.settings.value.activeMapFiles
        val surviving = selected.filterTo(HashSet()) { it in names }
        if (surviving.size != selected.size) settings.setActiveMapFiles(surviving)
    }

    /**
     * Copies [uri] into private storage and validates the copy, deleting it on failure.
     * Validated after copying since SAF doesn't promise a second open returns the same bytes.
     */
    suspend fun import(uri: Uri): MapImportResult =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val destination = uniqueFile(directory, resolver.displayName(uri), EXTENSION, fallback = "map")
            try {
                if (!resolver.copyInto(uri, destination)) {
                    destination.delete()
                    return@withContext MapImportResult.Failed(MapImportError.UNREADABLE)
                }
            } catch (_: IOException) {
                destination.delete()
                // Out of space is named separately since it's the user's to fix.
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

            val map = read(destination)
            if (map == null) {
                destination.delete()
                return@withContext MapImportResult.Failed(MapImportError.NOT_A_MAP_FILE)
            }
            readMaps[Key(destination.path, destination.lastModified())] = map

            // A new map replaces any active one it overlaps, since stacked renderings are
            // illegible. refresh() then drops them from the active set.
            val activeMaps = _maps.value.filter { it.file.name in settings.settings.value.activeMapFiles }
            activeMaps.filter { it.overlaps(map) }.forEach { it.file.delete() }

            refresh()
            settings.setActiveMapFiles(settings.settings.value.activeMapFiles + destination.name)
            MapImportResult.Imported(map)
        }

    /** Null if not a mapsforge map file. */
    private fun read(file: File): OfflineMap? {
        val header = MapFileHeader.read(file) ?: return null
        return OfflineMap(file = file, header = header, sizeBytes = file.length())
    }

    /** Hides [map] until [undoDelete] or [commitDelete]. */
    fun deleteLater(map: OfflineMap) {
        pendingDelete += map.file.name
        _maps.update { maps -> maps.filter { it.file != map.file } }
    }

    fun undoDelete(map: OfflineMap) {
        pendingDelete -= map.file.name
        scope.launch { refresh() }
    }

    fun commitDelete(map: OfflineMap) {
        scope.launch {
            map.file.delete()
            pendingDelete -= map.file.name
            refresh()
        }
    }

    private companion object {
        const val DIRECTORY = "maps"
        const val EXTENSION = "map"

        /** Below this, a failed copy is reported as out of space. */
        const val LOW_SPACE_BYTES = 64L * 1024 * 1024
    }
}
