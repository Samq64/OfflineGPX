package dev.samuelq.gpx.data.map

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import dev.samuelq.gpx.data.copyInto
import dev.samuelq.gpx.data.displayName
import dev.samuelq.gpx.data.size
import dev.samuelq.gpx.data.uniqueFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oscim.core.BoundingBox
import org.oscim.tiling.source.mapfile.header.MapFileHeader
import org.oscim.tiling.source.mapfile.header.SubFileParameter
import org.oscim.tiling.source.mapfile.readMapFileHeader
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An offline basemap the user has imported, and what its header says about it. */
class OfflineMap(
    val file: File,
    /** VTM's own, so a listed map is one its tile source opens. */
    private val header: MapFileHeader,
    val sizeBytes: Long,
) {
    val bounds: BoundingBox get() = header.mapFileInfo.boundingBox

    /** The comment carries the data credit; created-by is a fallback. */
    val attribution: String?
        get() = header.mapFileInfo.run { comment?.takeIf(String::isNotBlank) ?: createdBy?.takeIf(String::isNotBlank) }

    val displayName: String get() = file.nameWithoutExtension

    /** The one read for a tile at [zoom], clamped to the file's range as the reader does. */
    fun subFileFor(zoom: Int): SubFileParameter {
        val clamped = header.getQueryZoomLevel(zoom.coerceIn(0, Byte.MAX_VALUE.toInt()).toByte())
        return header.getSubFileParameter(clamped.toInt())
    }

    /** The one with the deepest stored tiles. */
    val deepest: SubFileParameter get() = subFileFor(Byte.MAX_VALUE.toInt())

    /** Where detail really stops: files claim more. */
    val baseZoom: Int get() = deepest.baseZoomLevel.toInt()

    internal fun movedTo(file: File) = OfflineMap(file, header, sizeBytes)

    /** Bounding-box overlap; true of most neighbours too, so see [duplicates]. */
    fun overlaps(other: OfflineMap): Boolean =
        bounds.minLongitude < other.bounds.maxLongitude &&
            other.bounds.minLongitude < bounds.maxLongitude &&
            bounds.minLatitude < other.bounds.maxLatitude &&
            other.bounds.minLatitude < bounds.maxLatitude

    /** Whether [other] covers the same place, not just a box that reaches over this one. */
    fun duplicates(other: OfflineMap): Boolean =
        overlaps(other) && (sharedData(this, other) ?: 1.0) >= DUPLICATE_SHARE

    companion object {
        /** Null unless a mapsforge map file VTM reads. Not a debug build, whose index has a signature. */
        internal fun read(file: File): OfflineMap? {
            val header = try {
                readMapFileHeader(file)
            } catch (_: IOException) {
                null
            }
            if (header == null || header.mapFileInfo.debugFile) return null
            return OfflineMap(file, header, file.length())
        }
    }
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
class MapStore(
    context: Context,
    /** Outlives Settings, so deletes after an undo lapses still finish. */
    private val scope: CoroutineScope,
) {

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

    /** Parsed headers, keyed by path and mtime so unchanged files aren't re-read each launch. */
    private val readMaps = ConcurrentHashMap<Key, OfflineMap>()

    private data class Key(val path: String, val modifiedAt: Long)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }

        val keys = files.associateBy { Key(it.path, it.lastModified()) }
        val found = keys
            .mapNotNull { (key, file) -> readMaps[key] ?: OfflineMap.read(file)?.also { readMaps[key] = it } }
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
            // Before copying, so a map too big doesn't fill the disk first.
            if (resolver.size(uri)?.let(::reserve) == false) {
                return@withContext MapImportResult.Failed(MapImportError.NO_SPACE)
            }
            val destination = uniqueFile(staging, resolver.displayName(uri), EXTENSION, fallback = "map")
            try {
                if (!resolver.copyInto(uri, destination)) {
                    destination.delete()
                    return@withContext MapImportResult.Failed(MapImportError.UNREADABLE)
                }
            } catch (_: IOException) {
                destination.delete()
                // Out of space is named separately since it's the user's to fix.
                val error = if (!hasRoomFor(LOW_SPACE_BYTES)) {
                    MapImportError.NO_SPACE
                } else {
                    MapImportError.UNREADABLE
                }
                return@withContext MapImportResult.Failed(error)
            } catch (_: SecurityException) {
                destination.delete()
                return@withContext MapImportResult.Failed(MapImportError.UNREADABLE)
            }

            val map = OfflineMap.read(destination)
            if (map == null) {
                destination.delete()
                return@withContext MapImportResult.Failed(MapImportError.NOT_A_MAP_FILE)
            }

            // Neighbours go straight in; a second copy of a place is asked about.
            val overlapping = _maps.value.filter { it.duplicates(map) }
            if (overlapping.isEmpty()) install(map) else MapImportResult.Overlaps(map, overlapping)
        }

    /** Whether [bytes] could be had on the maps' volume, counting cache the system would clear. */
    private fun hasRoomFor(bytes: Long): Boolean = withStorage(default = true) { storage, volume ->
        storage.getAllocatableBytes(volume) >= bytes
    }

    /** Has the system clear cache, if it must, to make room for [bytes]. False if it can't. */
    private fun reserve(bytes: Long): Boolean = withStorage(default = false) { storage, volume ->
        storage.getAllocatableBytes(volume) >= bytes && run {
            storage.allocateBytes(volume, bytes)
            true
        }
    }

    private inline fun withStorage(default: Boolean, block: (StorageManager, UUID) -> Boolean): Boolean {
        val storage = appContext.getSystemService<StorageManager>() ?: return default
        return try {
            block(storage, storage.getUuidForPath(directory))
        } catch (_: IOException) {
            default
        }
    }

    /** Keeps both; the renderer passes on repeated features once. */
    suspend fun confirmImport(overlaps: MapImportResult.Overlaps): MapImportResult =
        withContext(Dispatchers.IO) { install(overlaps.staged) }

    fun cancelImport(overlaps: MapImportResult.Overlaps) {
        scope.launch(Dispatchers.IO) { overlaps.staged.file.delete() }
    }

    private suspend fun install(staged: OfflineMap): MapImportResult {
        val destination = uniqueFile(directory, staged.file.name, EXTENSION, fallback = "map")
        if (!staged.file.renameTo(destination)) {
            staged.file.delete()
            return MapImportResult.Failed(MapImportError.UNREADABLE)
        }
        val map = staged.movedTo(destination)
        readMaps[Key(destination.path, destination.lastModified())] = map
        refresh()
        return MapImportResult.Imported(map)
    }

    /** Copies [map] to a document the user created. False if it couldn't be written. */
    suspend fun export(map: OfflineMap, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                map.file.inputStream().use { it.copyTo(output) }
            } != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
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
        scope.launch(Dispatchers.IO) {
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
