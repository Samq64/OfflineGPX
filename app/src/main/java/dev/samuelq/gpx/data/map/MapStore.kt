package dev.samuelq.gpx.data.map

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import dev.samuelq.gpx.data.copyInto
import dev.samuelq.gpx.data.displayName
import dev.samuelq.gpx.data.size
import dev.samuelq.gpx.data.uniqueFile
import dev.samuelq.gpx.data.uniqueName
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
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

    /** The same extract imported again: same area, same date, same size. Overlapping maps are drawn as one. */
    fun isSameAs(other: OfflineMap): Boolean = bounds == other.bounds &&
        header.mapFileInfo.mapDate == other.header.mapFileInfo.mapDate &&
        sizeBytes == other.sizeBytes

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

    ALREADY_IMPORTED,
}

sealed interface MapImportResult {
    class Imported(val map: OfflineMap) : MapImportResult

    class Failed(val error: MapImportError) : MapImportResult
}

/** Offline maps, copied into app-private storage since VTM's reader needs a seekable real path. */
class MapStore(
    context: Context,
    /** Outlives Settings, so deletes after an undo lapses still finish. */
    private val scope: CoroutineScope,
    /** For file and database work; a parameter so tests can substitute one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext

    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }

    /** Never transferred, and on [directory]'s filesystem, so installing is an atomic rename. */
    private val staging: File
        get() = File(appContext.noBackupFilesDir, STAGING).apply { mkdirs() }

    private val _maps = MutableStateFlow<List<OfflineMap>>(emptyList())

    /** Newest first; all drawn, overlapping or not. */
    val maps: StateFlow<List<OfflineMap>> = _maps.asStateFlow()

    /** Filenames deleted but still undoable; in memory, like pending track deletes. */
    private val pendingDelete = ConcurrentHashMap.newKeySet<String>()

    /** Parsed headers, keyed by path and mtime so unchanged files aren't re-read each launch. */
    private val readMaps = ConcurrentHashMap<Key, OfflineMap>()

    private data class Key(val path: String, val modifiedAt: Long)

    suspend fun refresh() = withContext(io) {
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
    suspend fun import(uri: Uri): MapImportResult = withContext(io) {
        // Only one import is ever pending, so anything here is a leftover.
        staging.listFiles()?.forEach { it.delete() }

        val resolver = appContext.contentResolver
        // Before copying, so a map too big doesn't fill the disk first.
        if (resolver.size(uri)?.let(::reserve) == false) {
            return@withContext MapImportResult.Failed(MapImportError.NO_SPACE)
        }
        val destination = uniqueFile(staging, resolver.displayName(uri), EXTENSION, fallback = "map")
        val copied = try {
            resolver.copyInto(uri, destination)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!copied) {
            destination.delete()
            return@withContext MapImportResult.Failed(MapImportError.UNREADABLE)
        }

        val map = OfflineMap.read(destination)
        if (map == null) {
            destination.delete()
            return@withContext MapImportResult.Failed(MapImportError.NOT_A_MAP_FILE)
        }

        if (_maps.value.any { it.isSameAs(map) }) {
            destination.delete()
            return@withContext MapImportResult.Failed(MapImportError.ALREADY_IMPORTED)
        }
        install(map)
    }

    /** Has the system clear cache, if it must, to make room for [bytes]. False if it can't. */
    private fun reserve(bytes: Long): Boolean {
        val storage = appContext.getSystemService<StorageManager>() ?: return false
        return try {
            storage.allocateBytes(storage.getUuidForPath(directory), bytes)
            true
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun install(staged: OfflineMap): MapImportResult {
        // A map awaiting delete under this name gives way rather than numbering the new one.
        if (pendingDelete.remove(staged.file.name)) File(directory, staged.file.name).delete()
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
    suspend fun export(map: OfflineMap, uri: Uri): Boolean = withContext(io) {
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

    /** Renames [map]'s file, numbered if taken. False if the rename failed. */
    suspend fun rename(map: OfflineMap, name: String): Boolean = withContext(io) {
        val target = renamed(map.file, name) ?: return@withContext false
        readMaps[Key(target.path, target.lastModified())] = map.movedTo(target)
        refresh()
        true
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

    /** Unless an import of the same name has already replaced it. */
    fun commitDelete(map: OfflineMap) {
        scope.launch(io) {
            if (pendingDelete.remove(map.file.name)) map.file.delete()
            refresh()
        }
    }

    internal companion object {
        private const val DIRECTORY = "maps"
        const val EXTENSION = "map"
        private const val STAGING = "map-staging"
    }
}

/**
 * Moves [file] to [name] beside it, numbered if taken, keeping its mtime and so its place in
 * the list. Null if the rename failed.
 */
internal fun renamed(file: File, name: String): File? {
    val dir = file.parentFile ?: return null
    val target = File(
        dir,
        uniqueName(name, MapStore.EXTENSION, fallback = "map") { it != file.name && File(dir, it).exists() },
    )
    return target.takeIf { it == file || file.renameTo(it) }
}
