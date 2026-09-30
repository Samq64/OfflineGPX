package dev.samuelq.gpx.data.map

import android.content.Context
import android.net.Uri
import dev.samuelq.gpx.data.copyInto
import dev.samuelq.gpx.data.displayName
import dev.samuelq.gpx.data.uniqueFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Bounding-box overlap; true of most neighbours too, so see [duplicates]. */
    fun overlaps(other: OfflineMap): Boolean =
        header.minLongitude < other.header.maxLongitude &&
            other.header.minLongitude < header.maxLongitude &&
            header.minLatitude < other.header.maxLatitude &&
            other.header.minLatitude < header.maxLatitude

    /** Whether [other] covers the same place, not just a box that reaches over this one. */
    fun duplicates(other: OfflineMap): Boolean =
        overlaps(other) && (sharedData(this, other) ?: 1.0) >= DUPLICATE_SHARE
}

enum class MapImportError {
    UNREADABLE,

    /** Not a mapsforge map file. */
    NOT_A_MAP_FILE,

    NO_SPACE,
}

sealed interface MapImportResult {
    class Imported(val map: OfflineMap) : MapImportResult

    /** Staged, awaiting [MapStore.confirmImport] or [MapStore.cancelImport]. */
    class Overlaps(val staged: OfflineMap, val existing: List<OfflineMap>) : MapImportResult

    class Failed(val error: MapImportError) : MapImportResult
}

/** Offline maps, copied into app-private storage since VTM's reader needs a seekable real path. */
class MapStore(context: Context) {

    private val appContext = context.applicationContext

    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }

    /** Inside [directory], so installing is a rename. `refresh()` skips directories. */
    private val staging: File
        get() = File(directory, STAGING).apply { mkdirs() }

    private val _maps = MutableStateFlow<List<OfflineMap>>(emptyList())

    /** Newest first; all drawn, overlapping or not. */
    val maps: StateFlow<List<OfflineMap>> = _maps.asStateFlow()

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
    }

    /**
     * Copies [uri] into staging and validates the copy, deleting it on failure. Validated
     * after copying since SAF doesn't promise a second open returns the same bytes.
     */
    suspend fun import(uri: Uri): MapImportResult =
        withContext(Dispatchers.IO) {
            // Only one import is ever pending, so anything here is a leftover.
            staging.listFiles()?.forEach { it.delete() }

            val resolver = appContext.contentResolver
            val destination = uniqueFile(staging, resolver.displayName(uri), EXTENSION, fallback = "map")
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

            // Neighbours go straight in; a second copy of a place is asked about.
            val overlapping = _maps.value.filter { it.duplicates(map) }
            if (overlapping.isEmpty()) install(map) else MapImportResult.Overlaps(map, overlapping)
        }

    /** Keeps both; the renderer passes on repeated features once. */
    suspend fun confirmImport(overlaps: MapImportResult.Overlaps): MapImportResult =
        withContext(Dispatchers.IO) { install(overlaps.staged) }

    fun cancelImport(overlaps: MapImportResult.Overlaps) {
        scope.launch { overlaps.staged.file.delete() }
    }

    private suspend fun install(staged: OfflineMap): MapImportResult {
        val destination = uniqueFile(directory, staged.file.name, EXTENSION, fallback = "map")
        if (!staged.file.renameTo(destination)) {
            staged.file.delete()
            return MapImportResult.Failed(MapImportError.UNREADABLE)
        }
        val map = OfflineMap(file = destination, header = staged.header, sizeBytes = staged.sizeBytes)
        readMaps[Key(destination.path, destination.lastModified())] = map
        refresh()
        return MapImportResult.Imported(map)
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
        const val STAGING = "staging"

        /** Below this, a failed copy is reported as out of space. */
        const val LOW_SPACE_BYTES = 64L * 1024 * 1024
    }
}
