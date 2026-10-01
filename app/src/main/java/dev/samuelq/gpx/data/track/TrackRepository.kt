package dev.samuelq.gpx.data.track

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.bounds
import dev.samuelq.gpx.data.db.ColorUse
import dev.samuelq.gpx.data.db.TrackDao
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.gpx.GpxNameRewriter
import dev.samuelq.gpx.data.gpx.GpxParseException
import dev.samuelq.gpx.data.gpx.GpxParser
import dev.samuelq.gpx.data.gpx.GpxWriter
import dev.samuelq.gpx.data.gpx.GPX_MIME_TYPE
import dev.samuelq.gpx.data.copyInto
import dev.samuelq.gpx.data.displayName
import dev.samuelq.gpx.data.uniqueFile
import dev.samuelq.gpx.data.sameBytes
import dev.samuelq.gpx.data.uniqueName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The single way the app gets at track data: Room rows plus the GPX files they index. */
class TrackRepository(
    context: Context,
    private val dao: TrackDao,
) {

    private val parser = GpxParser()
    private val writer = GpxWriter()
    private val io = Dispatchers.IO

    private val appContext = context.applicationContext

    /** Never cancelled: the repository is a process-lifetime singleton. */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val recordingsDir: File get() = TrackFiles.recordingsDir(appContext)
    private val importsDir: File get() = TrackFiles.importsDir(appContext)

    /** Parsed tracks on disk; what's drawn is held in memory by the map, not here. */
    private val cache = TrackCache(File(appContext.cacheDir, "tracks"))

    /**
     * Deleted but still undoable, so left out of every list. In memory only: if the process
     * dies first, the delete just doesn't happen.
     */
    private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())

    /** Most recent first. Shared, so the map and the list wake one query between them. */
    val tracks: Flow<List<TrackEntity>> =
        combine(dao.observeByRecent(), pendingDelete) { all, pending -> all.filter { it.id !in pending } }
            .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_GRACE_MILLIS), replay = 1)

    /**
     * Copies [uri] into app-private storage and indexes it. Returns the row id.
     *
     * @param reuseIdentical returns the existing row instead when an import of the same name has
     *   the same bytes, so opening a shared file twice doesn't add it twice. A deliberate import
     *   always copies.
     */
    suspend fun import(uri: Uri, reuseIdentical: Boolean = false): Result<Long> = withContext(io) {
        runCatching {
            val displayName = displayNameOf(uri)
            val destination = uniqueFile(importsDir, displayName, "gpx", fallback = "track")

            // Deleted again on any failure, so nothing unindexed is left behind.
            try {
                if (!appContext.contentResolver.copyInto(uri, destination)) {
                    throw TrackLoadException.Unreadable("No provider could open $uri")
                }
                if (reuseIdentical) {
                    identicalImport(destination, displayName)?.let { existing ->
                        destination.delete()
                        return@runCatching existing
                    }
                }
                val track = parse(destination.inputStream(), displayName)
                val id = dao.upsert(newEntity(destination, displayName, track, TrackAnalyzer.analyze(track)))
                cache.write(id, destination, track)
                id
            } catch (e: Throwable) {
                destination.delete()
                throw e
            }
        }.recoverFailure()
    }

    /** A live import named [displayName] whose file matches [copy] byte for byte. */
    private suspend fun identicalImport(copy: File, displayName: String): Long? {
        val pending = pendingDelete.value
        return dao.byDisplayName(displayName)
            .filter { it.id !in pending && it.location != TrackFiles.location(appContext, copy) }
            .firstOrNull { sameBytes(fileOf(it), copy) }
            ?.id
    }

    /** Loads a saved track and moves it to the top of the recent order. */
    suspend fun open(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching {
            load(entity(id)).also { dao.touch(id, System.currentTimeMillis()) }
        }.recoverFailure()
    }

    /**
     * Writes a finished recording as GPX and indexes it. Returns the new row id.
     *
     * @param analyzed the profile of this exact [track], if already computed.
     */
    suspend fun saveRecording(
        track: Track,
        analyzed: TrackProfile? = null,
    ): Result<Long> =
        withContext(io) {
            runCatching {
                val profile = analyzed ?: TrackAnalyzer.analyze(track)
                val startedAt = profile.stats.startedAt ?: Instant.now()
                // Numbered if taken: local time repeats an hour when the clocks go back.
                val file = uniqueFile(
                    recordingsDir,
                    FILE_STAMP.format(startedAt.atZone(ZoneId.systemDefault())),
                    "gpx",
                    fallback = "recording",
                )

                // Named inside the GPX so the name survives an export.
                val named = if (track.name.isNullOrBlank()) {
                    track.copy(name = defaultTrackName(appContext, profile.stats))
                } else {
                    track
                }
                file.outputStream().use { writer.write(named, it) }

                dao.upsert(newEntity(file, file.name, named, profile)).also { cache.write(it, file, named) }
            }.recoverFailure()
        }

    /** Writes tracks into the SAF folder [treeUri] under [names]. Returns how many landed. */
    suspend fun exportAll(names: Map<Long, String>, tree: Uri): Result<Int> =
        withContext(io) {
            runCatching {
                // A tree URI must be turned into a document URI before creating children.
                val folder = DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )

                // Lowercased: shared storage is case-insensitive, so `A.gpx` would collide with `a.gpx`.
                val taken = childNames(tree).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

                // Per track, so a failure halfway still reports the ones written.
                names.count { (id, name) ->
                    val unique = uniqueName(name, "gpx", fallback = "track") { it.lowercase(Locale.ROOT) in taken }
                    taken += unique.lowercase(Locale.ROOT)
                    runCatching { writeExport(folder, id, unique) }.getOrDefault(false)
                }
            }.recoverFailure()
        }

    /** What [tree] already holds, so exports can be numbered around it. */
    private fun childNames(tree: Uri): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return appContext.contentResolver
            .query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) } }
            .orEmpty()
    }

    /** Copies one track into [folder], deleting the document on failure to avoid a zero-byte file. */
    private suspend fun writeExport(folder: Uri, id: Long, name: String): Boolean {
        val entity = dao.byId(id) ?: return false
        val target = DocumentsContract.createDocument(
            appContext.contentResolver,
            folder,
            GPX_MIME_TYPE,
            name,
        ) ?: return false

        val written = try {
            appContext.contentResolver.openOutputStream(target)?.use { sink ->
                fileOf(entity).inputStream().use { it.copyTo(sink) }
            } != null
        } catch (e: Exception) {
            Log.d(TAG, "Could not export ${entity.displayName}", e)
            false
        }

        if (!written) {
            runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, target) }
        }
        return written
    }

    /** Reads a track without indexing it, for VIEW/SEND intents whose URI grants are one-shot. */
    /** Like [open] but without touching the sort order, for drawing. */
    suspend fun geometry(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching { load(entity(id)) }.recoverFailure()
    }

    /** From the cache when it's current, else parsed and cached. */
    private fun load(entity: TrackEntity): LoadedTrack {
        val file = fileOf(entity)
        cache.read(entity.id, file)?.let { track ->
            return LoadedTrack(entity.id, entity.displayName, track, TrackAnalyzer.analyze(track))
        }
        val track = parse(file.inputStream(), entity.displayName).also { cache.write(entity.id, file, it) }
        return LoadedTrack(entity.id, entity.displayName, track, TrackAnalyzer.analyze(track))
    }

    /** Bytes of each track's file, by id; a stat, not a parse. */
    suspend fun fileSizes(tracks: List<TrackEntity>): Map<Long, Long> = withContext(io) {
        tracks.associate { it.id to fileOf(it).length() }
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    /** Renames a track; a blank [name] clears it, falling back to the filename. */
    suspend fun rename(id: Long, name: String): Result<Unit> = withContext(io) {
        runCatching {
            val entity = entity(id)
            val trimmed = name.asTrackName()

            // Written into the file since export is a byte copy. Via a temp file so a
            // crash never truncates it.
            val file = fileOf(entity)
            val temp = File(file.parentFile, "${file.name}.tmp")
            try {
                if (GpxNameRewriter.rewrite(file, temp, trimmed)) {
                    if (!temp.renameTo(file)) throw TrackLoadException.Unreadable("Could not rewrite ${file.name}")
                    // The cache holds no name, so the rewrite leaves it current.
                    cache.restamp(id, file)
                }
            } finally {
                // No-op once renamed; cleans up on failure.
                temp.delete()
            }

            dao.setTrackName(id, trimmed)
        }.recoverFailure()
    }

    suspend fun setVisible(id: Long, visible: Boolean) = dao.setVisible(id, visible)

    suspend fun setAllVisible(visible: Boolean) = dao.setAllVisible(visible)

    suspend fun setColor(id: Long, colorIndex: Int) = dao.setColor(id, colorIndex.mod(TrackEntity.PALETTE_SIZE))

    /** Hides [ids] until [undoDelete] or [commitDelete]. */
    fun deleteLater(ids: Collection<Long>) = pendingDelete.update { it + ids }

    fun undoDelete(ids: Collection<Long>) = pendingDelete.update { it - ids.toSet() }

    /** In the repository's scope, so it finishes even as the screen that asked goes away. */
    fun commitDelete(ids: Collection<Long>) {
        scope.launch {
            val entities = dao.byIds(ids.toList())
            ids.forEach { dao.delete(it) }
            entities.forEach(::deleteFile)
            ids.forEach(cache::delete)
            pendingDelete.update { it - ids.toSet() }
        }
    }

    private suspend fun entity(id: Long): TrackEntity =
        dao.byId(id) ?: throw TrackLoadException.Unreadable("No track with id $id")

    private suspend fun newEntity(
        file: File,
        displayName: String,
        track: Track,
        profile: TrackProfile,
    ): TrackEntity {
        val stats = profile.stats
        val bounds = checkNotNull(track.points.bounds()) { "Indexing an empty track" }
        return TrackEntity(
            colorIndex = leastUsedSlot(dao.colorUsage(), TrackEntity.NEUTRAL_SLOT),
            location = TrackFiles.location(appContext, file),
            displayName = displayName,
            trackName = track.name,
            startedAtEpochMillis = stats.startedAt?.toEpochMilli(),
            lastOpenedAtEpochMillis = System.currentTimeMillis(),
            pointCount = stats.pointCount,
            distanceMeters = stats.distanceMeters,
            totalSeconds = stats.totalDurationSeconds,
            movingSeconds = stats.movingDurationSeconds,
            averageSpeedMps = stats.averageSpeedMps,
            ascentMeters = stats.ascentMeters,
            descentMeters = stats.descentMeters,
            southLatitude = bounds.southLatitude,
            westLongitude = bounds.westLongitude,
            northLatitude = bounds.northLatitude,
            eastLongitude = bounds.eastLongitude,
        )
    }

    /** Reads and analyses [stream], closing it. */
    /** Reads [stream], closing it. */
    private fun parse(stream: InputStream, displayName: String): Track {
        val track: Track = stream.use(parser::parse)
        if (track.isEmpty) throw TrackLoadException.Empty("No track points in $displayName")
        return track
    }

    private fun fileOf(entity: TrackEntity) = TrackFiles.file(appContext, entity.location)

    private fun deleteFile(entity: TrackEntity) {
        runCatching { fileOf(entity).delete() }
    }

    private fun displayNameOf(uri: Uri): String = appContext.contentResolver.displayName(uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "track.gpx"

    private companion object {
        const val TAG = "TrackRepository"

        /** Long enough to cover a rotation or a trip to another screen and back. */
        const val SHARE_GRACE_MILLIS = 5_000L

        /** `Locale.ROOT` keeps ASCII digits so filenames sort. */
        private val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", java.util.Locale.ROOT)

        /** Maps the read failures onto the three the UI has messages for. */
        fun <T> Result<T>.recoverFailure(): Result<T> = recoverCatching { e ->
            throw when (e) {
                is TrackLoadException -> e
                is GpxParseException -> TrackLoadException.Invalid(e.message ?: "Not valid GPX", e)
                is FileNotFoundException -> TrackLoadException.Unreadable("File no longer exists", e)
                // A transient URI grant can be revoked mid-read.
                is SecurityException ->
                    TrackLoadException.Unreadable("No longer permitted to read this file", e)

                is IOException ->
                    TrackLoadException.Unreadable(e.message ?: "Could not read the file", e)

                is OutOfMemoryError -> TrackLoadException.Unreadable("This file is too large", e)
                else -> e
            }
        }
    }
}

/**
 * Of the first [size] palette slots, the one fewest visible tracks use, then fewest overall,
 * then the lowest. Slots past [size] aren't counted.
 */
internal fun leastUsedSlot(usage: List<ColorUse>, size: Int): Int {
    val shown = IntArray(size)
    val all = IntArray(size)
    usage.forEach { use ->
        val slot = use.colorIndex.takeIf { it in 0 until size } ?: return@forEach
        all[slot]++
        if (use.visible) shown[slot]++
    }
    return (0 until size).minWith(compareBy({ shown[it] }, { all[it] }))
}
