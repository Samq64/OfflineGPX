package dev.samuelq.gpx.data.track

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.analysis.TrackStats
import dev.samuelq.gpx.core.model.Track
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

    /** Avoids reparsing the track the user just looked at; the sheet shows one at a time. */
    @Volatile
    private var cached: Pair<String, LoadedTrack>? = null

    /**
     * Deleted but still undoable, so left out of every list. In memory only: if the process
     * dies first, the delete just doesn't happen.
     */
    private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())

    /** Most recent first. Shared with [visibleTracks] so a write wakes one query. */
    val tracks: Flow<List<TrackEntity>> =
        combine(dao.observeByRecent(), pendingDelete) { all, pending -> all.filter { it.id !in pending } }
            .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_GRACE_MILLIS), replay = 1)

    /** Reversed so the most recently touched is painted last, on top. */
    val visibleTracks: Flow<List<TrackEntity>> =
        tracks.map { all -> all.filter(TrackEntity::visible).asReversed() }

    /** Copies [location] into app-private storage and indexes it. Returns the row id. No dedupe. */
    suspend fun import(location: String): Result<Long> = withContext(io) {
        runCatching {
            val uri = location.toUri()
            val displayName = displayNameOf(uri)
            val destination = uniqueFile(importsDir, displayName, "gpx", fallback = "track")

            // Deleted again if copy or parse fails, so nothing unusable is left behind.
            val loaded = runCatching {
                if (!appContext.contentResolver.copyInto(uri, destination)) {
                    throw TrackLoadException.Unreadable("No provider could open $uri")
                }
                read(destination.inputStream(), displayName, LoadedTrack.TRANSIENT_ID)
            }.onFailure { destination.delete() }.getOrThrow()

            val entity = newEntity(destination, displayName, loaded.track.name, loaded.profile.stats)
            val id = dao.upsert(entity)

            cached = entity.location to LoadedTrack(
                id = id,
                displayName = displayName,
                track = loaded.track,
                profile = loaded.profile,
                colorIndex = entity.colorIndex,
            )
            id
        }.recoverFailure()
    }

    suspend fun open(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching {
            val entity = entity(id)

            cached?.let { (location, track) ->
                if (location == entity.location) {
                    dao.touch(id, System.currentTimeMillis())
                    return@runCatching track
                }
            }

            val loaded = read(fileOf(entity).inputStream(), entity.displayName, id, entity.colorIndex)
            dao.touch(id, System.currentTimeMillis())
            cached = entity.location to loaded
            loaded
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

                val stats = profile.stats.copy(startedAt = startedAt)
                dao.upsert(newEntity(file, file.name, named.name, stats))
            }.recoverFailure()
        }

    /** Writes tracks into the SAF folder [treeUri] under [names]. Returns how many landed. */
    suspend fun exportAll(names: Map<Long, String>, treeUri: String): Result<Int> =
        withContext(io) {
            runCatching {
                val tree = treeUri.toUri()
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
    suspend fun openTransient(location: String): Result<LoadedTrack> = withContext(io) {
        runCatching {
            cached?.let { (cachedLocation, track) ->
                if (cachedLocation == location) return@runCatching track
            }
            val uri = location.toUri()
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: throw TrackLoadException.Unreadable("No provider could open $location")
            val loaded = read(stream, displayNameOf(uri), LoadedTrack.TRANSIENT_ID)
            cached = location to loaded
            loaded
        }.recoverFailure()
    }

    /** Like [open] but without touching the sort order, for map redraws. */
    suspend fun geometry(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching {
            val entity = entity(id)
            read(fileOf(entity).inputStream(), entity.displayName, id, entity.colorIndex)
        }.recoverFailure()
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
                if (GpxNameRewriter.rewrite(file, temp, trimmed) && !temp.renameTo(file)) {
                    throw TrackLoadException.Unreadable("Could not rewrite ${file.name}")
                }
            } finally {
                // No-op once renamed; cleans up on failure.
                temp.delete()
            }
            if (cached?.first == entity.location) cached = null

            dao.setTrackName(id, trimmed)
        }.recoverFailure()
    }

    suspend fun setVisible(id: Long, visible: Boolean) = dao.setVisible(id, visible)

    suspend fun setAllVisible(visible: Boolean) = dao.setAllVisible(visible)

    /** Hides [ids] until [undoDelete] or [commitDelete]. */
    fun deleteLater(ids: Collection<Long>) = pendingDelete.update { it + ids }

    fun undoDelete(ids: Collection<Long>) = pendingDelete.update { it - ids.toSet() }

    /** In the repository's scope, so it finishes even as the screen that asked goes away. */
    fun commitDelete(ids: Collection<Long>) {
        scope.launch {
            val entities = dao.byIds(ids.toList())
            if (entities.any { it.location == cached?.first }) cached = null
            ids.forEach { dao.delete(it) }
            entities.forEach(::deleteFile)
            pendingDelete.update { it - ids.toSet() }
        }
    }

    private suspend fun entity(id: Long): TrackEntity =
        dao.byId(id) ?: throw TrackLoadException.Unreadable("No track with id $id")

    private suspend fun newEntity(
        file: File,
        displayName: String,
        trackName: String?,
        stats: TrackStats,
    ) = TrackEntity(
        colorIndex = leastUsedSlot(dao.colorUsage(), TrackEntity.PALETTE_SIZE),
        location = TrackFiles.location(appContext, file),
        displayName = displayName,
        trackName = trackName,
        startedAtEpochMillis = stats.startedAt?.toEpochMilli(),
        lastOpenedAtEpochMillis = System.currentTimeMillis(),
        distanceMeters = stats.distanceMeters,
        totalSeconds = stats.totalDurationSeconds,
    )

    /** Reads and analyses [stream], closing it. */
    private fun read(
        stream: InputStream,
        displayName: String,
        id: Long,
        colorIndex: Int = 0,
    ): LoadedTrack {
        val track: Track = stream.use(parser::parse)
        if (track.isEmpty) throw TrackLoadException.Empty("No track points in $displayName")

        val profile: TrackProfile = TrackAnalyzer.analyze(track)
        return LoadedTrack(
            id = id,
            displayName = displayName,
            track = track,
            profile = profile,
            colorIndex = colorIndex,
        )
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

/** The palette slot fewest visible tracks use, then fewest overall, then the lowest. */
internal fun leastUsedSlot(usage: List<ColorUse>, size: Int): Int {
    val shown = IntArray(size)
    val all = IntArray(size)
    usage.forEach { use ->
        val slot = use.colorIndex.mod(size)
        all[slot]++
        if (use.visible) shown[slot]++
    }
    return (0 until size).minWith(compareBy({ shown[it] }, { all[it] }))
}
