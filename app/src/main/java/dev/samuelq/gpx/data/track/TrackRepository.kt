package dev.samuelq.gpx.data.track

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.bounds
import dev.samuelq.gpx.data.copyInto
import dev.samuelq.gpx.data.db.ColorUse
import dev.samuelq.gpx.data.db.SummaryUpdate
import dev.samuelq.gpx.data.db.TrackDao
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSummary
import dev.samuelq.gpx.data.displayName
import dev.samuelq.gpx.data.gpx.GPX_MIME_TYPE
import dev.samuelq.gpx.data.gpx.GpxParseException
import dev.samuelq.gpx.data.gpx.GpxParser
import dev.samuelq.gpx.data.gpx.GpxTrimmer
import dev.samuelq.gpx.data.gpx.GpxWriter
import dev.samuelq.gpx.data.runCancellable
import dev.samuelq.gpx.data.sameBytes
import dev.samuelq.gpx.data.uniqueFile
import dev.samuelq.gpx.data.uniqueName
import dev.samuelq.gpx.data.writeAtomically
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The single way the app gets at track data: Room rows plus the GPX files they index. */
class TrackRepository(
    context: Context,
    private val dao: TrackDao,
    /** Outlives any screen, so deletes and edits finish once asked for. */
    private val scope: CoroutineScope,
    /** For file and database work; a parameter so tests can substitute one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val parser = GpxParser()
    private val writer = GpxWriter()
    private val trimmer = GpxTrimmer()

    private val appContext = context.applicationContext

    private val recordingsDir: File get() = TrackFiles.recordingsDir(appContext)
    private val importsDir: File get() = TrackFiles.importsDir(appContext)

    /** Parsed tracks on disk; what's drawn is held in memory by the map, not here. */
    private val cache = TrackCache(File(appContext.cacheDir, "tracks"))

    /** Files an edit replaced, until its undo lapses. Not the cache, which the system may clear. */
    private val editsDir: File get() = File(appContext.noBackupFilesDir, "edits").apply { mkdirs() }

    /**
     * Deleted but still undoable, so left out of every list. In memory only: if the process
     * dies first, the delete just doesn't happen.
     */
    private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())

    /** Most recent first. Shared, so the map and the list wake one query between them. */
    val tracks: Flow<List<TrackEntity>> =
        combine(dao.observeByRecent(), pendingDelete) { all, pending -> all.filter { it.id !in pending } }
            .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_GRACE_MILLIS), replay = 1)

    /** Every category in use, alphabetical, to suggest; one spelling of each. */
    val categories: Flow<List<String>> = tracks.map { all ->
        val collator = Collator.getInstance()
        all.mapNotNull { it.category }.distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(collator)
    }

    /** The latest recording's, which the next one is offered. */
    val lastRecordingCategory: Flow<String?> = tracks.map { all ->
        all.filter(TrackFiles::isRecording).maxByOrNull(TrackEntity::id)?.category
    }

    /**
     * Copies [uri] into app-private storage and indexes it. Returns the row id.
     *
     * @param reuseIdentical returns the existing row instead when an import of the same name has
     *   the same bytes, so opening a shared file twice doesn't add it twice. A deliberate import
     *   always copies.
     */
    suspend fun import(uri: Uri, reuseIdentical: Boolean = false): Result<Long> = withContext(io) {
        runCancellable {
            val displayName = displayNameOf(uri)
            val destination = uniqueFile(importsDir, displayName, "gpx", fallback = "trace")

            // Deleted again on any failure, so nothing unindexed is left behind.
            try {
                if (!appContext.contentResolver.copyInto(uri, destination)) {
                    throw TrackLoadException.Unreadable("No provider could open $uri")
                }
                if (reuseIdentical) {
                    identicalImport(destination, displayName)?.let { existing ->
                        destination.delete()
                        return@runCancellable existing
                    }
                }
                val track = parse(destination.inputStream(), displayName)
                val category = categoryAsSpelt(track.type)
                val id = dao.upsert(newEntity(destination, displayName, track, TrackAnalyzer.analyze(track), category))
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
        runCancellable {
            load(entity(id)).also { dao.touch(id, System.currentTimeMillis()) }
        }.recoverFailure()
    }

    /**
     * Writes a finished recording as GPX and indexes it. Returns the new row id.
     *
     * @param analyzed the profile of this exact [track], if already computed.
     */
    suspend fun saveRecording(track: Track, analyzed: TrackProfile? = null): Result<Long> = withContext(io) {
        runCancellable {
            val profile = analyzed ?: TrackAnalyzer.analyze(track)
            val typed = track.copy(type = categoryAsSpelt(track.type))
            val startedAt = profile.stats.startedAt ?: Instant.now()
            // Numbered if taken: local time repeats an hour when the clocks go back.
            val file = uniqueFile(
                recordingsDir,
                TrackFiles.STAMP.format(startedAt.atZone(ZoneId.systemDefault())),
                "gpx",
                fallback = "recording",
            )

            // Unnamed unless given one: it's titled by when it started.
            writeAtomically(file) { writer.write(typed, it) }

            dao.upsert(newEntity(file, file.name, typed, profile, typed.type)).also { cache.write(it, file, typed) }
        }.recoverFailure()
    }

    /** Writes tracks into the SAF folder [treeUri] under [names]. Returns how many landed. */
    suspend fun exportAll(names: Map<Long, String>, tree: Uri): Result<Int> = withContext(io) {
        runCancellable {
            // A tree URI must be turned into a document URI before creating children.
            val folder = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )

            // Lowercased: shared storage is case-insensitive, so `A.gpx` would collide with `a.gpx`.
            val taken = childNames(tree).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

            // Per track, so a failure halfway still reports the ones written.
            names.count { (id, name) ->
                val unique = uniqueName(name, "gpx", fallback = "trace") { it.lowercase(Locale.ROOT) in taken }
                taken += unique.lowercase(Locale.ROOT)
                runCancellable { writeExport(folder, id, unique) }.getOrDefault(false)
            }
        }.recoverFailure()
    }

    /** What [tree] already holds, so exports can be numbered around it. */
    private fun childNames(tree: Uri): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
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

    /** Like [open] but without touching the sort order, for drawing. */
    suspend fun geometry(id: Long): Result<LoadedTrack> = withContext(io) {
        runCancellable { load(entity(id)) }.recoverFailure()
    }

    /** From the cache when it's current, else parsed and cached. */
    private fun load(entity: TrackEntity): LoadedTrack {
        val file = fileOf(entity)
        val track = cache.read(entity.id, file)
            ?: parse(file.inputStream(), entity.displayName).also { cache.write(entity.id, file, it) }
        return LoadedTrack(entity.id, entity.displayName, track, TrackAnalyzer.analyze(track))
    }

    /** Bytes of each track's file, by id; a stat, not a parse. */
    suspend fun fileSizes(tracks: List<TrackEntity>): Map<Long, Long> = withContext(io) {
        tracks.associate { it.id to fileOf(it).length() }
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    /**
     * Renames a track and its file, and sets its category; null leaves either as it is. A
     * blank [name] clears it, and the file goes back to the name it arrived as; a blank
     * [category] makes it uncategorised.
     */
    suspend fun rename(id: Long, name: String?, category: String? = null): Result<Unit> = withContext(io) {
        runCancellable {
            if (name == null && category == null) return@runCancellable
            val newName = name?.asTrackName()
            val newCategory = categoryAsSpelt(category, except = id)
            val entity = entity(id)
            // Written into the file since export is a byte copy.
            val file = fileOf(entity)
            writeAtomically(file) { output ->
                file.inputStream().use {
                    trimmer.trim(
                        it,
                        output,
                        name = name?.let {
                            newName.orEmpty()
                        },
                        type = category?.let { newCategory.orEmpty() },
                    )
                }
            }
            // The cache holds neither, so the rewrite leaves it current.
            cache.restamp(id, file)
            if (category != null) dao.setCategory(id, newCategory)
            if (name != null) {
                dao.setTrackName(id, newName)
                moveTo(id, file, newName ?: entity.displayName)
            }
        }.recoverFailure()
    }

    /**
     * Renames [file] after [name], numbered if taken. In place, so atomic; a rename that fails
     * leaves the old name, which still works.
     */
    private suspend fun moveTo(id: Long, file: File, name: String) {
        val dir = file.parentFile ?: return
        val target =
            File(dir, uniqueName(name, "gpx", fallback = "trace") { it != file.name && File(dir, it).exists() })
        if (target == file || !file.renameTo(target)) return
        try {
            dao.setLocation(id, TrackFiles.location(appContext, target))
        } catch (e: Throwable) {
            target.renameTo(file)
            throw e
        }
    }

    suspend fun setVisible(id: Long, visible: Boolean) = dao.setVisible(id, visible)

    suspend fun setVisible(ids: Collection<Long>, visible: Boolean) = dao.setVisible(ids.toList(), visible)

    suspend fun showOnly(ids: Collection<Long>) = dao.showOnly(ids.toList())

    /** Each track's visibility as in [visible], as an undo puts it back. */
    suspend fun restoreVisibility(visible: Map<Long, Boolean>) {
        val (shown, hidden) = visible.entries.partition { it.value }
        dao.setVisibility(shown.map { it.key }, hidden.map { it.key })
    }

    suspend fun setColor(id: Long, colorIndex: Int) = dao.setColor(id, colorIndex.mod(TrackEntity.PALETTE_SIZE))

    /** Hides [ids] until [undoDelete] or [commitDelete]. */
    fun deleteLater(ids: Collection<Long>) = pendingDelete.update { it + ids }

    fun undoDelete(ids: Collection<Long>) = pendingDelete.update { it - ids.toSet() }

    /** In the repository's scope, so it finishes even as the screen that asked goes away. */
    fun commitDelete(ids: Collection<Long>) {
        scope.launch(io) {
            val entities = dao.byIds(ids.toList())
            dao.delete(ids.toList())
            entities.forEach(::deleteFile)
            ids.forEach(cache::delete)
            pendingDelete.update { it - ids.toSet() }
        }
    }

    /**
     * Keeps points [from] to [to], inclusive, and the waypoints nearest them. In place, with
     * the original kept for [undoEdit] until [commitEdit].
     */
    suspend fun trim(id: Long, from: Int, to: Int): Result<TrackEdit> = withContext(io) {
        runCancellable {
            val entity = entity(id)
            val loaded = load(entity)
            require(from in 0 until to && to < loaded.track.points.size) { "Can't trim to $from..$to" }

            val file = fileOf(entity)
            val backup = backUp(file, id)
            val track = rewrite(file, loaded, from..to)
            dao.setSummary(summaryUpdate(id, track, TrackAnalyzer.analyze(track)))
            TrackEdit(id, backup, entity)
        }.recoverFailure()
    }

    /** Puts the file and row back as they were before [edit]. */
    suspend fun undoEdit(edit: TrackEdit): Result<Unit> = withContext(io) {
        runCancellable {
            // Where it is now: a rename since moves the file.
            writeAtomically(fileOf(dao.byId(edit.id) ?: edit.before)) { output ->
                edit.backup.inputStream().use { it.copyTo(output) }
            }
            // Only what the edit changed, so a recolour since survives the undo.
            dao.setSummary(SummaryUpdate(edit.id, edit.before.startedAtEpochMillis, edit.before.summary))
            dao.setTrackName(edit.id, edit.before.trackName)
            cache.delete(edit.id)
            edit.backup.delete()
            Unit
        }.recoverFailure()
    }

    /** The undo has lapsed. */
    fun commitEdit(edit: TrackEdit) {
        scope.launch(io) { edit.backup.delete() }
    }

    /** At launch: an undo from a previous process can no longer be taken. */
    fun purgeEdits() {
        scope.launch(io) { editsDir.listFiles().orEmpty().forEach(File::delete) }
    }

    private fun backUp(file: File, id: Long): File =
        File(editsDir, "$id-${System.currentTimeMillis()}.gpx").also { file.copyTo(it, overwrite = true) }

    /** Rewrites [file] to its points [keep], with the waypoints nearest them. Cached, and returned. */
    private fun rewrite(file: File, loaded: LoadedTrack, keep: IntRange): Track {
        val waypoints = loaded.track.waypoints.indices.filter { loaded.waypointIndices[it] in keep }.toSet()
        writeAtomically(file) { output ->
            file.inputStream().use { input ->
                trimmer.trim(
                    input,
                    output,
                    keepPoint = { it in keep },
                    keepWaypoint = { it in waypoints },
                    countRoutes = !file.inputStream().use(trimmer::hasTrackPoints),
                )
            }
        }
        val track = parse(file.inputStream(), file.name)
        cache.write(loaded.id, file, track)
        return track
    }

    /** [category] trimmed, in the spelling another track already gives it: categories ignore case. */
    private suspend fun categoryAsSpelt(category: String?, except: Long = 0): String? {
        val trimmed = category?.asTrackName() ?: return null
        return dao.categories(except).firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    private suspend fun entity(id: Long): TrackEntity =
        dao.byId(id) ?: throw TrackLoadException.Unreadable("No track with id $id")

    private suspend fun newEntity(
        file: File,
        displayName: String,
        track: Track,
        profile: TrackProfile,
        category: String?,
    ) = TrackEntity(
        colorIndex = leastUsedSlot(dao.colorUsage(), TrackEntity.PALETTE_SIZE),
        location = TrackFiles.location(appContext, file),
        displayName = displayName,
        trackName = track.name,
        startedAtEpochMillis = profile.stats.startedAt?.toEpochMilli(),
        lastOpenedAtEpochMillis = System.currentTimeMillis(),
        summary = summaryOf(track, profile),
        category = category,
    )

    private fun summaryUpdate(id: Long, track: Track, profile: TrackProfile) =
        SummaryUpdate(id, profile.stats.startedAt?.toEpochMilli(), summaryOf(track, profile))

    private fun summaryOf(track: Track, profile: TrackProfile): TrackSummary {
        val stats = profile.stats
        val bounds = checkNotNull(track.points.bounds()) { "Summarising an empty track" }
        return TrackSummary(
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
        ?: "trace.gpx"

    private companion object {
        const val TAG = "TrackRepository"

        /** Long enough to cover a rotation or a trip to another screen and back. */
        const val SHARE_GRACE_MILLIS = 5_000L

        /** Maps the read failures onto the three the UI has messages for. */
        fun <T> Result<T>.recoverFailure(): Result<T> = recoverCatching { e ->
            throw when (e) {
                is TrackLoadException -> e
                is GpxParseException -> TrackLoadException.Invalid(e.message ?: "Not valid GPX", e)
                is FileNotFoundException -> TrackLoadException.Unreadable("File no longer exists", e)
                // A temporary URI grant can be revoked mid-read.
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
