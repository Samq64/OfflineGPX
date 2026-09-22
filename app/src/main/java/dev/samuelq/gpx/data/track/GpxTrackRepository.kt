package dev.samuelq.gpx.data.track

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.db.TrackDao
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSource
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.gpx.GpxNameRewriter
import dev.samuelq.gpx.data.gpx.GpxParseException
import dev.samuelq.gpx.data.gpx.GpxParser
import dev.samuelq.gpx.data.gpx.GpxWriter
import dev.samuelq.gpx.data.record.RecordingService
import dev.samuelq.gpx.data.record.RecordingWal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GpxTrackRepository(
    context: Context,
    private val dao: TrackDao,
    private val parser: GpxParser = GpxParser(),
    private val writer: GpxWriter = GpxWriter(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrackRepository {

    private val appContext = context.applicationContext

    /**
     * Holds the shared table observer. Never needs cancelling - the repository is a
     * process-lifetime singleton; `WhileSubscribed` stops the query when nobody's watching.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val recordingsDir: File get() = RecordingService.recordingsDir(appContext)

    /**
     * App-private copy of every imported GPX, same as [recordingsDir] - a recording and an
     * import are both files this app owns outright, not borrowed via a grant.
     */
    private val importsDir: File get() = File(appContext.filesDir, "imports").apply { mkdirs() }

    /**
     * Single-entry cache: re-entering a track the user just looked at would otherwise
     * reparse tens of thousands of points for a visible stall. One entry, since the sheet
     * shows one track at a time.
     */
    @Volatile
    private var cached: Pair<String, LoadedTrack>? = null

    /**
     * One query behind both views of the table, shared rather than each opening its own
     * observer on the same statement (which woke two collectors per write). Grace period
     * long enough to survive a rotation or a trip to settings and back.
     */
    private val recent: Flow<List<TrackEntity>> = dao.observeByRecent()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_GRACE_MILLIS), replay = 1)

    override val tracks: Flow<List<TrackEntity>> get() = recent

    // Reversed, so the most recently touched track is drawn last and lands on top - the
    // same order the list shows, walked back to front.
    override val visibleTracks: Flow<List<TrackEntity>> =
        recent.map { all -> all.filter(TrackEntity::visible).asReversed() }

    override suspend fun import(location: String): Result<Long> = withContext(io) {
        runCatching {
            val uri = Uri.parse(location)
            val displayName = displayNameOf(uri)
            val destination = File(importsDir, uniqueImportName(displayName))

            // Copied before it's parsed, deleted again if either step fails - same order
            // MapStore validates a basemap in, so nothing unusable is left taking up space.
            val loaded = runCatching {
                copyToPrivateStorage(uri, destination)
                read(destination.absolutePath, displayName, LoadedTrack.TRANSIENT_ID)
            }.onFailure { destination.delete() }.getOrThrow()

            val now = System.currentTimeMillis()
            val stats = loaded.profile.stats
            val entity = TrackEntity(
                colorIndex = nextColorIndex(),
                source = TrackSource.IMPORTED,
                location = destination.absolutePath,
                displayName = displayName,
                trackName = stats.name,
                startedAtEpochMillis = stats.startedAt?.toEpochMilli(),
                lastOpenedAtEpochMillis = now,
                distanceMeters = stats.distanceMeters,
                movingSeconds = stats.movingDurationSeconds,
                totalSeconds = stats.totalDurationSeconds,
                ascentMeters = stats.ascentMeters,
                descentMeters = stats.descentMeters,
                pointCount = stats.pointCount,
            )
            val id = dao.upsert(entity)

            cached = destination.absolutePath to LoadedTrack(
                id = id,
                displayName = displayName,
                track = loaded.track,
                profile = loaded.profile,
                colorIndex = entity.colorIndex,
            )
            id
        }.recoverFailure()
    }

    /** Copies [source] into [destination], or throws if nothing could be read from it. */
    private fun copyToPrivateStorage(source: Uri, destination: File) {
        val copied = appContext.contentResolver.openInputStream(source)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied == null) {
            throw TrackLoadException.Unreadable("No provider could open $source")
        }
    }

    override suspend fun open(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching {
            val entity = dao.byId(id)
                ?: throw TrackLoadException.Unreadable("No track with id $id")

            cached?.let { (location, track) ->
                if (location == entity.location) {
                    dao.touch(id, System.currentTimeMillis())
                    return@runCatching track
                }
            }

            val loaded = read(entity.location, entity.displayName, id, entity.colorIndex)
            dao.touch(id, System.currentTimeMillis())
            cached = entity.location to loaded
            loaded
        }.recoverFailure()
    }

    override suspend fun saveRecording(
        track: Track,
        startedAt: Instant,
        analyzed: TrackProfile?,
    ): Result<Long> =
        withContext(io) {
            runCatching {
                val profile = analyzed ?: TrackAnalyzer.analyze(track)
                val displayName = recordingFileName(startedAt)
                val file = File(recordingsDir, displayName)

                // Named before it's written, so the name is inside the GPX and survives an
                // export. The filename itself stays the sortable stamp - nobody reads those.
                val named = if (track.name.isNullOrBlank()) {
                    track.copy(name = defaultName(profile.stats))
                } else {
                    track
                }
                file.outputStream().use { writer.write(named, it) }

                val stats = profile.stats.copy(name = named.name)
                val entity = TrackEntity(
                    source = TrackSource.RECORDED,
                    colorIndex = nextColorIndex(),
                    location = file.absolutePath,
                    displayName = displayName,
                    trackName = stats.name,
                    startedAtEpochMillis = stats.startedAt?.toEpochMilli()
                        ?: startedAt.toEpochMilli(),
                    lastOpenedAtEpochMillis = System.currentTimeMillis(),
                    distanceMeters = stats.distanceMeters,
                    movingSeconds = stats.movingDurationSeconds,
                    totalSeconds = stats.totalDurationSeconds,
                    ascentMeters = stats.ascentMeters,
                    descentMeters = stats.descentMeters,
                    pointCount = stats.pointCount,
                )
                dao.upsert(entity)
            }.recoverFailure()
        }

    override suspend fun recoverAbandonedRecording(): Long? = withContext(io) {
        val claimed = File(recordingsDir, RecordingService.WAL_RECOVERY_NAME)
        // Claimed by renaming before a byte is read: a recording started while this is
        // still parsing opens WAL_NAME in append mode, and without the rename that live
        // log is the one the deletes below would take. A claim left by a recovery that
        // died mid-way is finished rather than overwritten.
        if (!claimed.exists()) {
            val log = File(recordingsDir, RecordingService.WAL_NAME)
            if (!log.exists() || log.length() == 0L) return@withContext null
            if (!log.renameTo(claimed)) return@withContext null
        }

        // Timestamps come from the fixes themselves, so a recovered ride is dated when it
        // happened rather than when the app next opened.
        val track = RecordingWal.recover(claimed, name = null)
        if (track == null) {
            claimed.delete()
            return@withContext null
        }

        // The same bar a clean stop applies: a crash must not resurrect what pressing Stop
        // would have thrown away.
        val profile = TrackAnalyzer.analyze(track)
        if (profile.stats.distanceMeters < RecordingService.MIN_SAVEABLE_DISTANCE_METERS) {
            claimed.delete()
            return@withContext null
        }

        val startedAt = track.segments.firstOrNull()?.points?.firstOrNull()?.time ?: Instant.now()
        saveRecording(track, startedAt, profile).getOrNull()?.also { claimed.delete() }
    }

    override suspend fun exportAll(names: Map<Long, String>, treeUri: String): Result<Int> =
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
            GPX_MIME,
            name,
        ) ?: return false

        val written = try {
            appContext.contentResolver.openOutputStream(target)?.use { sink ->
                openStream(entity.location).use { it.copyTo(sink) }
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

    override suspend fun openTransient(location: String): Result<LoadedTrack> = withContext(io) {
        runCatching {
            cached?.let { (cachedLocation, track) ->
                if (cachedLocation == location) return@runCatching track
            }
            val loaded = read(location, displayNameOf(Uri.parse(location)), LoadedTrack.TRANSIENT_ID)
            cached = location to loaded
            loaded
        }.recoverFailure()
    }

    override suspend fun geometry(id: Long): Result<LoadedTrack> = withContext(io) {
        runCatching {
            val entity = dao.byId(id)
                ?: throw TrackLoadException.Unreadable("No track with id $id")
            read(entity.location, entity.displayName, id, entity.colorIndex)
        }.recoverFailure()
    }

    override suspend fun touch(id: Long) = withContext(io) {
        dao.touch(id, System.currentTimeMillis())
    }

    override suspend fun rename(id: Long, name: String): Result<Unit> = withContext(io) {
        runCatching {
            val entity = dao.byId(id)
                ?: throw TrackLoadException.Unreadable("No track with id $id")
            val trimmed = name.trim().takeIf(String::isNotEmpty)

            // Written into the file too: export is a byte copy, so a rename that never
            // touched it wouldn't survive one. Spliced rather than reparsed and rewritten
            // - see [GpxNameRewriter]. Via a temp file, so a crash never truncates it.
            val file = File(entity.location)
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

    override suspend fun setVisible(ids: List<Long>, visible: Boolean) = withContext(io) {
        dao.setVisible(ids, visible)
    }

    override suspend fun setAllVisible(visible: Boolean) = withContext(io) {
        dao.setAllVisible(visible)
    }

    override suspend fun forgetAll(ids: List<Long>) = withContext(io) {
        if (ids.isEmpty()) return@withContext
        val entities = dao.byIds(ids)
        if (entities.any { it.location == cached?.first }) cached = null
        ids.forEach { dao.delete(it) }
        entities.forEach(::deleteFile)
    }

    /**
     * What a recording is called before anyone renames it: time of day plus walk-or-ride,
     * inferred from average moving speed (hiking 3-6 km/h, cycling 15-30, safely apart). A
     * wrong guess costs one rename; the row underneath already carries date/distance/duration.
     */
    private fun defaultName(stats: dev.samuelq.gpx.core.analysis.TrackStats): String {
        val zoned = (stats.startedAt ?: Instant.now()).atZone(ZoneId.systemDefault())
        val activity = if (stats.averageSpeedMps < WALKING_SPEED_CEILING_MPS) {
            R.string.track_default_walk
        } else {
            R.string.track_default_ride
        }
        val partOfDay = when (zoned.hour) {
            in 5..11 -> R.string.track_default_morning
            in 12..16 -> R.string.track_default_afternoon
            in 17..20 -> R.string.track_default_evening
            else -> R.string.track_default_night
        }
        return appContext.getString(partOfDay, appContext.getString(activity))
    }

    /** Round-robin over the palette, so a handful of tracks rarely collide on a hue. */
    private suspend fun nextColorIndex(): Int = dao.count() % ROUTE_PALETTE_SIZE

    /**
     * Reads and analyses whatever [location] points at: an app-private path, or a
     * transient `content://` URI for [openTransient].
     */
    private fun read(
        location: String,
        displayName: String,
        id: Long,
        colorIndex: Int = 0,
    ): LoadedTrack {
        val track: Track = openStream(location).use(parser::parse)
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

    private fun openStream(location: String) =
        if (location.startsWith("/")) {
            File(location).inputStream()
        } else {
            appContext.contentResolver.openInputStream(Uri.parse(location))
                ?: throw TrackLoadException.Unreadable("No provider could open $location")
        }

    /** Both sources are this app's own file now, so deleting the row deletes it. */
    private fun deleteFile(entity: TrackEntity) {
        runCatching { File(entity.location).delete() }
    }

    private fun displayNameOf(uri: Uri): String = queryDisplayName(uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "track.gpx"

    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            }
    } catch (e: Exception) {
        Log.d(TAG, "Could not query a display name for $uri", e)
        null
    }

    /**
     * A name in [importsDir] not already taken, keeping the picked file's own name where
     * possible - same reasoning as `MapStore`'s equivalent.
     */
    private fun uniqueImportName(displayName: String): String {
        val base = displayName
            .substringAfterLast('/')
            .let { if (it.endsWith(".gpx", ignoreCase = true)) it.dropLast(4) else it }
            .replace(UNSAFE_FILENAME_CHARACTERS, "_")
            .take(MAX_IMPORT_NAME_LENGTH)
            .ifBlank { "track" }

        var candidate = "$base.gpx"
        var suffix = 2
        while (File(importsDir, candidate).exists()) {
            candidate = "$base ($suffix).gpx"
            suffix++
        }
        return candidate
    }

    private companion object {
        const val TAG = "GpxTrackRepository"

        /** Matches `routePalette()` in the theme. Six, then hues repeat. */
        const val ROUTE_PALETTE_SIZE = 6

        /** Long enough to cover a rotation or a trip to another screen and back. */
        const val SHARE_GRACE_MILLIS = 5_000L

        /** 9 km/h. Above a brisk walk, well below a bicycle. */
        const val WALKING_SPEED_CEILING_MPS = 2.5

        /** What a provider is told an exported track is, so it files it as one. */
        const val GPX_MIME = "application/gpx+xml"

        const val MAX_IMPORT_NAME_LENGTH = 80
        val UNSAFE_FILENAME_CHARACTERS = Regex("""[\\/:*?"<>|]""")

        /**
         * Sortable, unambiguous, and legible as a filename once exported. `Locale.ROOT` so
         * the digits are the ones that sort - `ofPattern` otherwise takes its
         * `DecimalStyle` from the default locale, numerals and all.
         */
        private val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", java.util.Locale.ROOT)

        fun recordingFileName(startedAt: Instant): String =
            "${FILE_STAMP.format(startedAt.atZone(ZoneId.systemDefault()))}.gpx"

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
