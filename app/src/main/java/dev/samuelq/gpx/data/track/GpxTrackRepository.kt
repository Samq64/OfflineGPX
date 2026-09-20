package dev.samuelq.gpx.data.track

import android.content.Context
import android.content.Intent
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
import dev.samuelq.gpx.data.gpx.GpxParseException
import dev.samuelq.gpx.data.gpx.GpxParser
import dev.samuelq.gpx.data.gpx.GpxWriter
import dev.samuelq.gpx.data.record.RecordingService
import dev.samuelq.gpx.data.record.RecordingWal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    private val recordingsDir: File get() = RecordingService.recordingsDir(appContext)

    /**
     * Single-entry cache: re-entering a track the user just looked at would otherwise
     * reparse 30k points for a visible stall. One entry, because the sheet shows one track
     * and an unbounded map would hold every track the user opened.
     */
    @Volatile
    private var cached: Pair<String, LoadedTrack>? = null

    override val tracks: Flow<List<TrackEntity>> get() = dao.observeByRecent()

    // Reversed, so the most recently touched track is drawn last and lands on top - the
    // same order the list shows, walked back to front.
    override val visibleTracks: Flow<List<TrackEntity>> =
        dao.observeByRecent().map { all -> all.filter(TrackEntity::visible).asReversed() }

    override suspend fun import(location: String): Result<Long> = withContext(io) {
        runCatching {
            val uri = Uri.parse(location)
            takePersistablePermission(uri)

            val displayName = displayNameOf(uri)
            val loaded = read(location, displayName, LoadedTrack.TRANSIENT_ID)
            val now = System.currentTimeMillis()

            // Look the row up rather than relying on @Upsert to resolve the unique index:
            // upsert falls back to updating by primary key, which an id of 0 would miss.
            val existing = dao.byLocation(location)
            val existingId = existing?.id
            val stats = loaded.profile.stats
            val entity = TrackEntity(
                id = existingId ?: 0,
                // Re-importing keeps the colour it already had; a track changing hue
                // because it was opened twice would be baffling.
                colorIndex = existing?.colorIndex ?: nextColorIndex(),
                visible = existing?.visible ?: true,
                source = TrackSource.IMPORTED,
                location = location,
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
            val insertedId = dao.upsert(entity)
            val id = existingId ?: insertedId

            cached = location to LoadedTrack(
                id = id,
                displayName = displayName,
                track = loaded.track,
                profile = loaded.profile,
                colorIndex = entity.colorIndex,
            )
            id
        }.recoverFailure()
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

    override suspend fun saveRecording(track: Track, startedAt: Instant): Result<Long> =
        withContext(io) {
            runCatching {
                val profile = TrackAnalyzer.analyze(track)
                val displayName = recordingFileName(startedAt)
                val file = File(recordingsDir, displayName)

                // Named before it is written, so the name is inside the GPX and survives
                // an export. The filename stays the sortable stamp - that is for the
                // filesystem, and nobody reads a list of those.
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
        val log = File(recordingsDir, RecordingService.WAL_NAME)
        if (!log.exists() || log.length() == 0L) return@withContext null

        // Timestamps come from the fixes themselves, so a recovered ride is dated when it
        // happened rather than when the app next opened.
        val track = RecordingWal.recover(log, name = null)
        if (track == null) {
            log.delete()
            return@withContext null
        }

        // The same bar a clean stop applies. A crash must not resurrect a recording that
        // pressing Stop would have thrown away - the log is the only difference between
        // the two, and it is not a difference the user made.
        if (TrackAnalyzer.analyze(track).stats.distanceMeters <
            RecordingService.MIN_SAVEABLE_DISTANCE_METERS
        ) {
            log.delete()
            return@withContext null
        }

        val startedAt = track.segments.firstOrNull()?.points?.firstOrNull()?.time ?: Instant.now()
        saveRecording(track, startedAt).getOrNull()?.also { log.delete() }
    }

    override suspend fun export(id: Long, destination: String): Result<Unit> = withContext(io) {
        runCatching {
            val entity = dao.byId(id)
                ?: throw TrackLoadException.Unreadable("No track with id $id")

            val output = appContext.contentResolver.openOutputStream(Uri.parse(destination))
                ?: throw TrackLoadException.Unreadable("Could not write to $destination")

            // A copy, not a re-serialisation: what is on disk is already correct GPX, and
            // regenerating it would let the exported file drift from the stored one.
            output.use { sink -> openStream(entity.location).use { it.copyTo(sink) } }
            // copyTo returns the byte count; the caller only needs to know it worked.
            Unit
        }.recoverFailure()
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

                names.count { (id, name) ->
                    val entity = dao.byId(id)
                    // The provider resolves a name that is already taken by adding a
                    // number, so two rides called the same thing cost nothing here.
                    val target = entity?.let {
                        DocumentsContract.createDocument(
                            appContext.contentResolver,
                            folder,
                            GPX_MIME,
                            name,
                        )
                    }
                    if (target == null) {
                        false
                    } else {
                        appContext.contentResolver.openOutputStream(target)?.use { sink ->
                            openStream(entity.location).use { it.copyTo(sink) }
                        } != null
                    }
                }
            }.recoverFailure()
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

            if (entity.source == TrackSource.RECORDED) {
                // Rewrite via a temp file: a crash partway through would otherwise leave
                // the recording truncated, which is the one outcome worth engineering out.
                val file = File(entity.location)
                val parsed = file.inputStream().use(parser::parse)
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.outputStream().use { writer.write(parsed.copy(name = trimmed), it) }
                if (!temp.renameTo(file)) {
                    temp.delete()
                    throw TrackLoadException.Unreadable("Could not rewrite ${file.name}")
                }
                if (cached?.first == entity.location) cached = null
            }

            dao.setTrackName(id, trimmed)
        }.recoverFailure()
    }

    override suspend fun setVisible(ids: List<Long>, visible: Boolean) = withContext(io) {
        dao.setVisible(ids, visible)
    }

    override suspend fun setAllVisible(visible: Boolean) = withContext(io) {
        dao.setAllVisible(visible)
    }

    override suspend fun forget(id: Long) = forgetAll(listOf(id))

    override suspend fun forgetAll(ids: List<Long>) = withContext(io) {
        if (ids.isEmpty()) return@withContext
        val entities = dao.byIds(ids)
        if (entities.any { it.location == cached?.first }) cached = null
        ids.forEach { dao.delete(it) }
        entities.forEach(::releaseOrDelete)
    }

    override suspend fun clearAll() = withContext(io) {
        // Read the rows before dropping them: releasing the grants needs their locations.
        val all = dao.all()
        cached = null
        dao.deleteAll()
        all.forEach(::releaseOrDelete)
    }

    /**
     * What a recording is called before anyone renames it.
     *
     * "2026-09-19T110233.gpx" is a filename, not a name - it sorts, and that is all it
     * does for a reader scanning a list. The time of day is what people actually reach for
     * ("the ride on Sunday morning"), and the row underneath already carries the date, the
     * distance and the duration, so the headline does not have to repeat any of them.
     *
     * Walk or ride is inferred from average moving speed. The two are far enough apart -
     * hiking is 3-6 km/h and cycling 15-30 - that a threshold between them is safe, and a
     * wrong guess costs one rename.
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

    /** Reads and analyses whatever [location] points at, SAF URI or app-private path. */
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

    /** The user's file stays theirs; a recording is ours, so its file goes with the row. */
    private fun releaseOrDelete(entity: TrackEntity) {
        when (entity.source) {
            TrackSource.IMPORTED -> releasePersistablePermission(Uri.parse(entity.location))
            TrackSource.RECORDED -> runCatching { File(entity.location).delete() }
        }
    }

    /**
     * Converts the picker's one-session grant into one that survives a reboot, so a
     * library row still works tomorrow. This is why the app needs no storage permission:
     * the grant covers exactly the file the user chose.
     */
    private fun takePersistablePermission(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Expected for URIs never offered as persistable. The file still opens now.
            Log.d(TAG, "Grant for $uri is not persistable", e)
        }
    }

    private fun releasePersistablePermission(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "No persisted grant to release for $uri", e)
        }
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

    private companion object {
        const val TAG = "GpxTrackRepository"

        /** Matches `routePalette()` in the theme. Six, then hues repeat. */
        const val ROUTE_PALETTE_SIZE = 6

        /** 9 km/h. Above a brisk walk, well below a bicycle. */
        const val WALKING_SPEED_CEILING_MPS = 2.5

        /** What a provider is told an exported track is, so it files it as one. */
        const val GPX_MIME = "application/gpx+xml"

        /** Sortable, unambiguous, and legible as a filename once exported. */
        private val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")

        fun recordingFileName(startedAt: Instant): String =
            "${FILE_STAMP.format(startedAt.atZone(ZoneId.systemDefault()))}.gpx"

        /** Maps the read failures onto the three the UI has messages for. */
        fun <T> Result<T>.recoverFailure(): Result<T> = recoverCatching { e ->
            throw when (e) {
                is TrackLoadException -> e
                is GpxParseException -> TrackLoadException.Invalid(e.message ?: "Not valid GPX", e)
                is FileNotFoundException -> TrackLoadException.Unreadable("File no longer exists", e)
                // A persisted grant can be revoked by a reboot on some providers, or by
                // the user clearing the picker's permissions.
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
