package dev.samuelq.gpx.data.track

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.db.TrackDao
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSource
import dev.samuelq.gpx.data.gpx.GpxParseException
import dev.samuelq.gpx.data.gpx.GpxParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class GpxTrackRepository(
    context: Context,
    private val dao: TrackDao,
    private val parser: GpxParser = GpxParser(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrackRepository {

    private val appContext = context.applicationContext

    /**
     * Single-entry cache: re-entering a track the user just looked at would otherwise
     * reparse 30k points for a visible stall. One entry, because nothing shows two tracks
     * at once and an unbounded map would hold every track the user opened.
     */
    @Volatile
    private var cached: Pair<String, LoadedTrack>? = null

    override val tracks: Flow<List<TrackEntity>> get() = dao.observeAll()

    override suspend fun import(location: String): Result<Long> = withContext(io) {
        runCatching {
            val uri = Uri.parse(location)
            takePersistablePermission(uri)

            val displayName = displayNameOf(uri)
            val loaded = read(location, displayName, LoadedTrack.TRANSIENT_ID)
            val now = System.currentTimeMillis()

            // Look the row up rather than relying on @Upsert to resolve the unique index:
            // upsert falls back to updating by primary key, which an id of 0 would miss.
            val existingId = dao.byLocation(location)?.id
            val stats = loaded.profile.stats
            val entity = TrackEntity(
                id = existingId ?: 0,
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

            cached = location to LoadedTrack(id, displayName, loaded.track, loaded.profile)
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

            val loaded = read(entity.location, entity.displayName, id)
            dao.touch(id, System.currentTimeMillis())
            cached = entity.location to loaded
            loaded
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

    override suspend fun forget(id: Long) = withContext(io) {
        val entity = dao.byId(id) ?: return@withContext
        if (cached?.first == entity.location) cached = null
        dao.delete(id)
        releaseOrDelete(entity)
    }

    override suspend fun clearAll() = withContext(io) {
        // Read the rows before dropping them: releasing the grants needs their locations.
        val all = dao.all()
        cached = null
        dao.deleteAll()
        all.forEach(::releaseOrDelete)
    }

    /** Reads and analyses whatever [location] points at, SAF URI or app-private path. */
    private fun read(location: String, displayName: String, id: Long): LoadedTrack {
        val track: Track = openStream(location).use(parser::parse)
        if (track.isEmpty) throw TrackLoadException.Empty("No track points in $displayName")

        val profile: TrackProfile = TrackAnalyzer.analyze(track)
        return LoadedTrack(id = id, displayName = displayName, track = track, profile = profile)
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
