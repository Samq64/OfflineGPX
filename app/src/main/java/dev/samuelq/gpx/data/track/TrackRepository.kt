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
import dev.samuelq.gpx.data.gpx.GpxTooLargeException
import dev.samuelq.gpx.data.gpx.GpxTrimmer
import dev.samuelq.gpx.data.gpx.GpxWriter
import dev.samuelq.gpx.data.runCancellable
import dev.samuelq.gpx.data.sameBytes
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.uniqueName
import dev.samuelq.gpx.data.writeAtomically
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.Collator
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
    private val settings: SettingsRepository,
    /** Outlives any screen, so deletes and edits finish once asked for. */
    private val scope: CoroutineScope,
    /** For file and database work; a parameter so tests can substitute one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val parser = GpxParser()
    private val writer = GpxWriter()
    private val trimmer = GpxTrimmer()

    private val appContext = context.applicationContext

    /** Parsed tracks on disk; what's drawn is held in memory by the map, not here. */
    private val cache = TrackCache(File(appContext.cacheDir, "tracks"))

    /** Files an edit replaced, until its undo lapses. Not the cache, which the system may clear. */
    private val editsDir: File get() = File(appContext.noBackupFilesDir, "edits").apply { mkdirs() }

    /** Copies being shared; `res/xml/file_paths.xml` shares them. */
    private val sharedDir: File get() = File(appContext.cacheDir, "shared")

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

    /** The slot the next new track gets, so a recording is drawn in it while under way. */
    val nextColorSlot: Flow<Int> = dao.observeByRecent().map { all ->
        leastUsedSlot(all.map { ColorUse(it.colorIndex, it.visible) }, TrackEntity.PALETTE_SIZE)
    }

    /** The last saved recording's, which the next one is offered. */
    val lastRecordingCategory: Flow<String?> = settings.lastRecordingCategory

    /**
     * Copies [uri] into app-private storage and indexes it. Returns the row id. Named by its
     * `<name>`, else by the file it arrived as; from then on only the row is.
     *
     * @param reuseIdentical returns the existing row instead when a track's file has the same
     *   bytes, so opening a shared file twice doesn't add it twice. A deliberate import always copies.
     */
    suspend fun import(uri: Uri, reuseIdentical: Boolean = false): Result<Long> = withContext(io) {
        runCancellable {
            val arrivedAs = displayNameOf(uri)
            val staged = stagedFile()
            // Gone once moved in; deleted on any failure, so nothing unindexed is left behind.
            try {
                if (!appContext.contentResolver.copyInto(uri, staged)) {
                    throw TrackLoadException.Unreadable("No provider could open $uri")
                }
                if (reuseIdentical) identicalTrack(staged)?.let { return@runCancellable it }
                val track = parse(staged.inputStream(), arrivedAs)
                if (!track.points.let { points -> (0 until points.size).all(points::hasTime) }) {
                    throw TrackLoadException.Untimed("Points without times in $arrivedAs")
                }
                val entity = newEntity(
                    track,
                    TrackAnalyzer.analyze(track),
                    name = track.name ?: arrivedAs.withoutGpxSuffix().asTrackName(),
                    category = categoryAsSpelt(track.type),
                    colorIndex = track.displayColor?.let(RouteColors::slotOf),
                )
                insert(entity, staged, track)
            } finally {
                staged.delete()
            }
        }.recoverFailure()
    }

    /** A live track whose file matches [copy] byte for byte. A rename leaves the file as it came. */
    private suspend fun identicalTrack(copy: File): Long? {
        val pending = pendingDelete.value
        val length = copy.length()
        return dao.all()
            .filter { it.id !in pending }
            .firstOrNull { fileOf(it).length() == length && sameBytes(fileOf(it), copy) }
            ?.id
    }

    /** Indexes [staged] as a new track, moving it in within the transaction that adds its row. */
    private suspend fun insert(entity: TrackEntity, staged: File, track: Track): Long {
        // Over any file a rolled-back insert left under this id.
        val id = dao.insert(entity) { id -> moveInto(staged, fileOf(id)) }
        cache.write(id, fileOf(id), track)
        return id
    }

    private fun stagedFile(): File = File.createTempFile("track", ".gpx", TrackFiles.stagingDir(appContext))

    /** Atomic, both being on one filesystem; inside a transaction, so its row changes with it. */
    private fun moveInto(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Could not move the track into place")
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
            val staged = stagedFile()
            try {
                writeAtomically(staged) { writer.write(typed, it) }
                // Unnamed unless given one: it's titled by when it started.
                insert(newEntity(typed, profile, name = typed.name, category = typed.type), staged, typed)
                    .also { settings.setLastRecordingCategory(typed.type) }
            } finally {
                staged.delete()
            }
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
                val unique = uniqueName(name, "gpx", fallback = "track") { it.lowercase(Locale.ROOT) in taken }
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
                fileOf(entity).inputStream().use { writeOut(entity, it, sink) }
            } != null
        } catch (e: Exception) {
            Log.d(TAG, "Could not export track ${entity.id}", e)
            false
        }

        if (!written) {
            runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, target) }
        }
        return written
    }

    /**
     * A copy of the track's file to share, carrying its name, category and colour, or the file
     * itself if the copy fails. Kept until the next launch, since the recipient may read it late.
     */
    suspend fun fileToShare(entity: TrackEntity): File = withContext(io) {
        val file = fileOf(entity)
        val dir = File(sharedDir, entity.id.toString())
        val copy = File(dir, entity.exportFileName)
        runCancellable {
            dir.deleteRecursively()
            dir.mkdirs()
            writeAtomically(copy) { output -> file.inputStream().use { writeOut(entity, it, output) } }
            copy
        }.getOrElse { e ->
            Log.d(TAG, "Sharing track ${entity.id} as stored", e)
            file
        }
    }

    /** The stored file as it leaves the app: name, category and colour live in the row, so they go in here. */
    private fun writeOut(entity: TrackEntity, input: InputStream, output: OutputStream) = trimmer.trim(
        input,
        output,
        // Blank removes the file's own.
        name = entity.trackName.orEmpty(),
        type = entity.category.orEmpty(),
        color = RouteColors.garminName(entity.colorIndex),
    )

    /** Like [open] but without touching the sort order, for drawing. */
    suspend fun geometry(id: Long): Result<LoadedTrack> = withContext(io) {
        runCancellable { load(entity(id)) }.recoverFailure()
    }

    /** From the cache when it's current, else parsed and cached. */
    private fun load(entity: TrackEntity): LoadedTrack {
        val file = fileOf(entity)
        val track = cache.read(entity.id, file)
            ?: parse(file.inputStream(), file.name).also { cache.write(entity.id, file, it) }
        return LoadedTrack(entity.id, track, TrackAnalyzer.analyze(track))
    }

    /** Bytes of each track's file, by id; a stat, not a parse. */
    suspend fun fileSizes(tracks: List<TrackEntity>): Map<Long, Long> = withContext(io) {
        tracks.associate { it.id to fileOf(it).length() }
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    /**
     * Renames a track and sets its category; null leaves either as it is. A blank [name] clears
     * it, titling the track by its start; a blank [category] makes it uncategorised. The row's
     * alone: [writeOut] puts both in a file as it leaves.
     */
    suspend fun rename(id: Long, name: String?, category: String? = null): Result<Unit> = withContext(io) {
        runCancellable {
            if (category != null) dao.setCategory(id, categoryAsSpelt(category, except = id))
            if (name != null) dao.setTrackName(id, name.asTrackName())
        }.recoverFailure()
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
            val staged = stagedFile()
            try {
                val track = trimmed(file, staged, loaded, from..to)
                dao.setSummary(summaryUpdate(id, track, TrackAnalyzer.analyze(track))) { moveInto(staged, file) }
                cache.write(id, file, track)
            } catch (e: Throwable) {
                backup.delete()
                throw e
            } finally {
                staged.delete()
            }
            TrackEdit(id, backup, entity.summary)
        }.recoverFailure()
    }

    /** Puts the file and summary back as they were before [edit]; the rest of the row is left. */
    suspend fun undoEdit(edit: TrackEdit): Result<Unit> = withContext(io) {
        runCancellable {
            dao.setSummary(SummaryUpdate(edit.id, edit.before)) { moveInto(edit.backup, fileOf(edit.id)) }
            cache.delete(edit.id)
        }.recoverFailure()
    }

    /** The undo has lapsed. */
    fun commitEdit(edit: TrackEdit) {
        scope.launch(io) { edit.backup.delete() }
    }

    /**
     * At launch: an undo from a previous process can no longer be taken, nor a share still be read,
     * nor an import or recording it was staging be finished. A track file or cache entry without
     * a row, as a crash mid-delete or an undo after one leaves, goes too.
     */
    fun purgeAtLaunch() {
        val launchedAt = System.currentTimeMillis()
        scope.launch(io) {
            editsDir.listFiles().orEmpty().forEach(File::delete)
            sharedDir.deleteRecursively()
            // Older than this process only: an import from the launching intent may be under way.
            TrackFiles.stagingDir(appContext).listFiles().orEmpty()
                .filter { it.lastModified() < launchedAt }
                .forEach(File::delete)
            val ids = dao.ids().toSet()
            TrackFiles.dir(appContext).listFiles().orEmpty()
                .filter { it.lastModified() < launchedAt && it.name.removeSuffix(".gpx").toLongOrNull() !in ids }
                .forEach(File::delete)
            cache.retain(ids, before = launchedAt)
        }
    }

    private fun backUp(file: File, id: Long): File =
        File(editsDir, "$id-${System.currentTimeMillis()}.gpx").also { file.copyTo(it, overwrite = true) }

    /** Writes [file]'s points [keep], with the waypoints nearest them, to [into]; returned parsed. */
    private fun trimmed(file: File, into: File, loaded: LoadedTrack, keep: IntRange): Track {
        val waypoints = loaded.track.waypoints.indices.filter { loaded.waypointIndices[it] in keep }.toSet()
        writeAtomically(into) { output ->
            file.inputStream().use { input ->
                trimmer.trim(
                    input,
                    output,
                    keepPoint = { it in keep },
                    keepWaypoint = { it in waypoints },
                )
            }
        }
        return parse(into.inputStream(), file.name)
    }

    /** [category] trimmed, in the spelling another track already gives it: categories ignore case. */
    private suspend fun categoryAsSpelt(category: String?, except: Long = 0): String? {
        val trimmed = category?.asTrackName() ?: return null
        return dao.categories(except).firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    private suspend fun entity(id: Long): TrackEntity =
        dao.byId(id) ?: throw TrackLoadException.Unreadable("No track with id $id")

    private suspend fun newEntity(
        track: Track,
        profile: TrackProfile,
        name: String?,
        category: String?,
        colorIndex: Int? = null,
    ) = TrackEntity(
        colorIndex = colorIndex ?: leastUsedSlot(dao.colorUsage(), TrackEntity.PALETTE_SIZE),
        trackName = name,
        lastOpenedAtEpochMillis = System.currentTimeMillis(),
        summary = summaryOf(track, profile),
        category = category,
    )

    private fun summaryUpdate(id: Long, track: Track, profile: TrackProfile) =
        SummaryUpdate(id, summaryOf(track, profile))

    private fun summaryOf(track: Track, profile: TrackProfile): TrackSummary {
        val stats = profile.stats
        return TrackSummary(
            // Imports are refused without times and recordings always have them, as does any part of either.
            startedAtEpochMillis = checkNotNull(stats.startedAt) { "A track without a start" }.toEpochMilli(),
            distanceMeters = stats.distanceMeters,
            totalSeconds = stats.totalDurationSeconds,
            bounds = checkNotNull(track.points.bounds()) { "Summarising an empty track" },
        )
    }

    /** Reads [stream], closing it; [source] names it in errors. */
    private fun parse(stream: InputStream, source: String): Track {
        val track: Track = stream.use(parser::parse)
        if (track.isEmpty) throw TrackLoadException.Empty("No track points in $source")
        return track
    }

    private fun fileOf(entity: TrackEntity) = fileOf(entity.id)

    private fun fileOf(id: Long) = TrackFiles.file(appContext, id)

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

        /** Maps the read failures onto those the UI has messages for. */
        fun <T> Result<T>.recoverFailure(): Result<T> = recoverCatching { e ->
            throw when (e) {
                is TrackLoadException -> e
                is GpxParseException -> TrackLoadException.Invalid(e.message ?: "Not valid GPX", e)
                is GpxTooLargeException -> TrackLoadException.TooLarge(e.message ?: "Too many points", e)
                is FileNotFoundException -> TrackLoadException.Unreadable("File no longer exists", e)
                // A temporary URI grant can be revoked mid-read.
                is SecurityException ->
                    TrackLoadException.Unreadable("No longer permitted to read this file", e)

                is IOException ->
                    TrackLoadException.Unreadable(e.message ?: "Could not read the file", e)

                is OutOfMemoryError -> TrackLoadException.TooLarge("Out of memory", e)
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
