package dev.samuelq.gpx.data.track

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The single way the app gets at track data: Room rows plus the GPX files they index. */
class TrackRepository(
    context: Context,
    private val dao: TrackDao,
) {

    private val parser = GpxParser()
    private val writer = GpxWriter()
    private val io = Dispatchers.IO

    private val appContext = context.applicationContext

    /**
     * Holds the shared table observer. Never needs cancelling - the repository is a
     * process-lifetime singleton; `WhileSubscribed` stops the query when nobody's watching.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val recordingsDir: File get() = TrackFiles.recordingsDir(appContext)
    private val importsDir: File get() = TrackFiles.importsDir(appContext)

    /**
     * Single-entry cache: re-entering a track the user just looked at would otherwise
     * reparse tens of thousands of points for a visible stall. One entry, since the sheet
     * shows one track at a time.
     */
    @Volatile
    private var cached: Pair<String, LoadedTrack>? = null

    /**
     * Most recently interacted with first, the app's only ordering. One shared query behind
     * this and [visibleTracks], so a write wakes one observer rather than two.
     */
    val tracks: Flow<List<TrackEntity>> = dao.observeByRecent()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_GRACE_MILLIS), replay = 1)

    /**
     * What the map draws: [tracks] filtered to the visible ones and reversed, so the most
     * recently touched is painted last and lands on top.
     */
    val visibleTracks: Flow<List<TrackEntity>> =
        tracks.map { all -> all.filter(TrackEntity::visible).asReversed() }

    /**
     * Copies [location] into app-private storage, reads it, and indexes the copy. Returns
     * the row id. No dedupe - picking the same file twice makes two rows, like recording
     * twice does.
     */
    suspend fun import(location: String): Result<Long> = withContext(io) {
        runCatching {
            val uri = Uri.parse(location)
            val displayName = displayNameOf(uri)
            val destination = uniqueFile(importsDir, displayName, "gpx", fallback = "track")

            // Copied before it's parsed, deleted again if either step fails - same order
            // MapStore validates a basemap in, so nothing unusable is left taking up space.
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
     * Writes a finished recording to app-private storage as GPX and indexes it, named and
     * dated by its first point. Returns the new row id.
     *
     * @param analyzed the profile of this exact [track], when the caller already has one -
     *   recovery does, and re-deriving it walks every point again for the same answer.
     */
    suspend fun saveRecording(
        track: Track,
        analyzed: TrackProfile? = null,
    ): Result<Long> =
        withContext(io) {
            runCatching {
                val profile = analyzed ?: TrackAnalyzer.analyze(track)
                // Every recorded point is timed; the fallback is for a track that isn't.
                val startedAt = profile.stats.startedAt ?: Instant.now()
                // The start time, numbered if taken: local time repeats an hour when the
                // clocks go back, and a save must never overwrite another ride.
                val file = uniqueFile(
                    recordingsDir,
                    FILE_STAMP.format(startedAt.atZone(ZoneId.systemDefault())),
                    "gpx",
                    fallback = "recording",
                )

                // Named before it's written, so the name is inside the GPX and survives an
                // export. The filename itself stays the sortable stamp - nobody reads those.
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

    /**
     * Writes several tracks into [treeUri], a folder chosen through SAF, under the [names]
     * given here. Returns how many landed - one unwritable name doesn't cost the rest.
     */
    suspend fun exportAll(names: Map<Long, String>, treeUri: String): Result<Int> =
        withContext(io) {
            runCatching {
                val tree = Uri.parse(treeUri)
                // A tree URI is not a document URI: the folder has to be named as the
                // document it also is before anything can be created inside it.
                val folder = DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )

                // One runCatching per track, not one around the batch: a folder filling up
                // halfway is exactly what the count exists to report, and letting that
                // escape would report zero for a folder holding twenty files.
                names.count { (id, name) ->
                    runCatching { writeExport(folder, id, name) }.getOrDefault(false)
                }
            }.recoverFailure()
        }

    /**
     * Copies one track into [folder]. Deletes the document again if the copy fails: it is
     * created before it can be written, and a zero-byte `.gpx` in the user's own folder is
     * worse than the track simply not being there.
     */
    private suspend fun writeExport(folder: Uri, id: Long, name: String): Boolean {
        val entity = dao.byId(id) ?: return false
        // The provider resolves a name that is already taken by adding a number, so two
        // rides called the same thing cost nothing here.
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

    /**
     * Reads a track without indexing it, for VIEW and SEND intents. Those URIs are
     * one-shot grants, so a library row for one would only fail when tapped.
     */
    suspend fun openTransient(location: String): Result<LoadedTrack> = withContext(io) {
        runCatching {
            cached?.let { (cachedLocation, track) ->
                if (cachedLocation == location) return@runCatching track
            }
            val uri = Uri.parse(location)
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: throw TrackLoadException.Unreadable("No provider could open $location")
            val loaded = read(stream, displayNameOf(uri), LoadedTrack.TRANSIENT_ID)
            cached = location to loaded
            loaded
        }.recoverFailure()
    }

    /**
     * Reads a track's geometry without recording that it was opened - the map drawing a
     * track isn't the user looking at it, and [open] would rewrite the sort order every redraw.
     */
    suspend fun geometry(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching {
            val entity = entity(id)
            read(fileOf(entity).inputStream(), entity.displayName, id, entity.colorIndex)
        }.recoverFailure()
    }

    /** Records that the user touched this track, which is what the map stacks on. */
    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    /**
     * Renames a track. A blank [name] clears it, falling the row back to its filename.
     *
     * The GPX on disk is rewritten too, whichever source it came from, so an export
     * carries the name the user gave it rather than the one the file arrived with.
     */
    suspend fun rename(id: Long, name: String): Result<Unit> = withContext(io) {
        runCatching {
            val entity = entity(id)
            val trimmed = name.trim().takeIf(String::isNotEmpty)

            // Written into the file too: export is a byte copy, so a rename that never
            // touched it wouldn't survive one. Spliced rather than reparsed and rewritten
            // - see [GpxNameRewriter]. Via a temp file, so a crash never truncates it.
            val file = fileOf(entity)
            val temp = File(file.parentFile, "${file.name}.tmp")
            try {
                if (GpxNameRewriter.rewrite(file, temp, trimmed) && !temp.renameTo(file)) {
                    throw TrackLoadException.Unreadable("Could not rewrite ${file.name}")
                }
            } finally {
                // A no-op once renamed; this is for the failure paths, which would
                // otherwise leave a half-written .tmp behind for good.
                temp.delete()
            }
            if (cached?.first == entity.location) cached = null

            dao.setTrackName(id, trimmed)
        }.recoverFailure()
    }

    suspend fun setVisible(ids: List<Long>, visible: Boolean) = dao.setVisible(ids, visible)

    suspend fun setAllVisible(visible: Boolean) = dao.setAllVisible(visible)

    suspend fun forgetAll(ids: List<Long>) = withContext(io) {
        if (ids.isEmpty()) return@withContext
        val entities = dao.byIds(ids)
        if (entities.any { it.location == cached?.first }) cached = null
        ids.forEach { dao.delete(it) }
        entities.forEach(::deleteFile)
    }

    private suspend fun entity(id: Long): TrackEntity =
        dao.byId(id) ?: throw TrackLoadException.Unreadable("No track with id $id")

    /** A new row for [file], summarised by [stats], in the palette slot least in use. */
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

    /** Every track is this app's own file, so deleting the row deletes it. */
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

        /**
         * Sortable, unambiguous, and legible as a filename once exported. `Locale.ROOT` so
         * the digits are the ones that sort - `ofPattern` otherwise takes its
         * `DecimalStyle` from the default locale, numerals and all.
         */
        private val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", java.util.Locale.ROOT)

        /** Maps the read failures onto the three the UI has messages for. */
        fun <T> Result<T>.recoverFailure(): Result<T> = recoverCatching { e ->
            throw when (e) {
                is TrackLoadException -> e
                is GpxParseException -> TrackLoadException.Invalid(e.message ?: "Not valid GPX", e)
                is FileNotFoundException -> TrackLoadException.Unreadable("File no longer exists", e)
                // Only a transient, one-shot URI reaches this - an import's own copy needs
                // no grant - and that access can still be revoked mid-read.
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
 * The palette slot fewest tracks on the map use, then fewest overall, then the lowest.
 * A round-robin over the row count repeated a colour still in use after any delete.
 */
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
