package dev.samuelq.gpx.data.track

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.data.db.GpxDatabase
import dev.samuelq.gpx.data.db.SummaryUpdate
import dev.samuelq.gpx.data.db.TrackDao
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.gpx.GpxParser
import dev.samuelq.gpx.fixture
import dev.samuelq.gpx.sampleGpx
import dev.samuelq.gpx.targetContext
import dev.samuelq.gpx.waitFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against the real filesystem and an in-memory database. */
@RunWith(AndroidJUnit4::class)
class TrackRepositoryTest {

    private lateinit var database: GpxDatabase
    private lateinit var dao: TrackDao
    private lateinit var scope: CoroutineScope
    private lateinit var repository: TrackRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(targetContext, GpxDatabase::class.java).build()
        dao = database.trackDao()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = TrackRepository(targetContext, dao, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun importSample(content: String = sampleGpx(), name: String = "sample-${System.nanoTime()}.gpx"): Long =
        runBlocking { repository.import(Uri.fromFile(fixture(name, content))).getOrThrow() }

    private fun row(id: Long): TrackEntity = runBlocking { dao.byId(id)!! }

    private fun fileOf(id: Long): File = TrackFiles.file(targetContext, row(id).location)

    private fun parsed(id: Long): Track = fileOf(id).inputStream().use(GpxParser()::parse)

    private fun loaded(id: Long): LoadedTrack = runBlocking { repository.geometry(id).getOrThrow() }

    private fun importsFiles(): Set<String> =
        TrackFiles.importsDir(targetContext).list().orEmpty().toSet()

    @Test
    fun importIndexesTheCopy() {
        val id = importSample()
        val entity = row(id)

        assertTrue(entity.location.startsWith("imports/"))
        assertTrue(fileOf(id).exists())
        assertEquals("Test ride", entity.trackName)
        assertEquals(20, entity.summary.pointCount)
        assertTrue(entity.distanceMeters > 200)
        assertEquals(38.0, entity.totalSeconds)
        assertEquals(Instant.parse("2024-05-04T09:00:00Z").toEpochMilli(), entity.startedAtEpochMillis)
        assertNotNull(entity.bounds)

        val track = loaded(id)
        assertEquals(20, track.track.points.size)
        assertEquals(1, track.track.waypoints.size)
    }

    @Test
    fun reimportingIdenticalSharedFileReusesTheRow() {
        val name = "shared-${System.nanoTime()}.gpx"
        val file = fixture(name, sampleGpx())
        runBlocking {
            val first = repository.import(Uri.fromFile(file), reuseIdentical = true).getOrThrow()
            val before = importsFiles()
            val again = repository.import(Uri.fromFile(file), reuseIdentical = true).getOrThrow()
            assertEquals(first, again)
            assertEquals(before, importsFiles())

            // A deliberate import always copies.
            val copy = repository.import(Uri.fromFile(file)).getOrThrow()
            assertNotEquals(first, copy)
        }
    }

    @Test
    fun failedImportsLeaveNothingBehind() {
        val before = importsFiles()
        runBlocking {
            val invalid = repository.import(Uri.fromFile(fixture("bad.gpx", "<gpx><trk><trkseg><trkpt"))).exceptionOrNull()
            assertIs<TrackLoadException.Invalid>(invalid)

            val waypointsOnly = repository.import(Uri.fromFile(fixture("empty.gpx", sampleGpx(points = 0)))).exceptionOrNull()
            assertIs<TrackLoadException.Empty>(waypointsOnly)

            val missing = repository.import(Uri.fromFile(File(targetContext.cacheDir, "missing.gpx"))).exceptionOrNull()
            assertIs<TrackLoadException.Unreadable>(missing)
        }
        assertEquals(before, importsFiles())
        assertTrue(runBlocking { repository.tracks.first() }.isEmpty())
    }

    @Test
    fun openTouchesAndUnknownIdFails() {
        val id = importSample()
        runBlocking {
            dao.touch(id, 0)
            val track = repository.open(id).getOrThrow()
            assertEquals(id, track.id)
            assertTrue(row(id).lastOpenedAtEpochMillis > 0)
            assertIs<TrackLoadException.Unreadable>(repository.open(9_999).exceptionOrNull())
        }
    }

    @Test
    fun renameWritesTheFileAndRow() {
        val id = importSample()
        runBlocking { repository.rename(id, "  Evening loop ").getOrThrow() }
        assertEquals("Evening loop", row(id).trackName)
        assertEquals("Evening loop", parsed(id).name)
        // Still served from the cache, now restamped.
        assertEquals(20, loaded(id).track.points.size)

        runBlocking { repository.rename(id, " ").getOrThrow() }
        assertNull(row(id).trackName)
        assertNull(parsed(id).name)
        assertEquals(20, parsed(id).points.size)
    }

    @Test
    fun importTakesTheFilesType() {
        assertNull(row(importSample()).category)
        val typed = importSample(sampleGpx().replace("<trkseg>", "<type>trail_running</type><trkseg>"))
        // As written, not tidied.
        assertEquals("trail_running", row(typed).category)
        // But in the spelling a category already has.
        val again = importSample(sampleGpx().replace("<trkseg>", "<type>Trail_Running</type><trkseg>"))
        assertEquals("trail_running", row(again).category)
    }

    @Test
    fun showOnlyHidesEveryOtherTrack() {
        val kept = importSample()
        val hidden = importSample()
        val shown = importSample()
        runBlocking {
            repository.setVisible(listOf(hidden), false)
            repository.showOnly(listOf(hidden, shown))
        }
        assertFalse(row(kept).visible)
        assertTrue(row(hidden).visible)
        assertTrue(row(shown).visible)
    }

    @Test
    fun restoreVisibilityPutsEachBack() {
        val shown = importSample()
        val hidden = importSample()
        runBlocking {
            repository.setVisible(listOf(hidden), false)
            repository.showOnly(listOf(hidden))
            repository.restoreVisibility(mapOf(shown to true, hidden to false))
        }
        assertTrue(row(shown).visible)
        assertFalse(row(hidden).visible)
    }

    @Test
    fun categoryTakesTheSpellingInUseElseItsOwn() {
        val first = importSample()
        val second = importSample()
        runBlocking { repository.rename(first, name = null, category = "Hikes ${first}").getOrThrow() }
        runBlocking { repository.rename(second, name = null, category = "HIKES ${first}").getOrThrow() }
        assertEquals("Hikes $first", row(second).category)
        assertEquals("Hikes $first", parsed(second).type)
        // Alone in it, a track can respell its own.
        val alone = importSample()
        runBlocking { repository.rename(alone, name = null, category = "solo $alone").getOrThrow() }
        runBlocking { repository.rename(alone, name = null, category = "Solo $alone").getOrThrow() }
        assertEquals("Solo $alone", row(alone).category)
    }

    @Test
    fun categoryIsSetAloneInTheFileAndRow() {
        val id = importSample()
        val file = fileOf(id)
        runBlocking { repository.rename(id, name = null, category = " Hikes ").getOrThrow() }
        assertEquals("Hikes", row(id).category)
        assertEquals("Hikes", parsed(id).type)
        assertEquals("Test ride", row(id).trackName)
        assertEquals(file, fileOf(id))

        runBlocking { repository.rename(id, name = null, category = " ").getOrThrow() }
        assertNull(row(id).category)
        assertNull(parsed(id).type)
    }

    @Test
    fun renameMovesTheFileAfterTheName() {
        val name = "sample-${System.nanoTime()}.gpx"
        val id = importSample(name = name)
        val before = fileOf(id)
        runBlocking { repository.rename(id, "Tom & Jerry's: ride ${System.nanoTime()}").getOrThrow() }
        val renamed = row(id)
        assertEquals("${renamed.trackName!!.replace(':', '_')}.gpx", File(renamed.location).name)
        assertFalse(before.exists())
        assertTrue(fileOf(id).exists())
        assertEquals(20, loaded(id).track.points.size)
        // Still the name it arrived as.
        assertEquals(name, renamed.displayName)

        // Taken by another: numbered.
        val other = importSample()
        runBlocking { repository.rename(other, renamed.trackName!!).getOrThrow() }
        assertEquals(File(renamed.location).nameWithoutExtension + " (2).gpx", File(row(other).location).name)

        // Cleared: back to the name it arrived as.
        runBlocking { repository.rename(id, " ").getOrThrow() }
        assertEquals(name, File(row(id).location).name)
    }

    @Test
    fun undoingATrimFindsARenamedFile() {
        val id = importSample()
        val original = fileOf(id).readBytes()
        val edit = runBlocking { repository.trim(id, 5, 14).getOrThrow() }
        val trimmedAt = fileOf(id)
        runBlocking { repository.rename(id, "Renamed ${System.nanoTime()}").getOrThrow() }
        assertFalse(trimmedAt.exists())

        runBlocking { repository.undoEdit(edit).getOrThrow() }
        assertFalse(trimmedAt.exists(), "nothing written where it was")
        assertEquals(20, loaded(id).track.points.size)
        assertContentEquals(original, fileOf(id).readBytes())
    }

    @Test
    fun trimAndUndo() {
        val id = importSample()
        val original = fileOf(id).readBytes()

        val edit = runBlocking { repository.trim(id, 5, 14).getOrThrow() }
        assertEquals(10, row(id).summary.pointCount)
        assertEquals(10, loaded(id).track.points.size)
        // The waypoint by point 15 is outside what's kept.
        assertTrue(loaded(id).track.waypoints.isEmpty())
        assertTrue(edit.backup.exists())

        runBlocking { repository.undoEdit(edit).getOrThrow() }
        assertContentEquals(original, fileOf(id).readBytes())
        assertEquals(20, row(id).summary.pointCount)
        assertEquals(20, loaded(id).track.points.size)
        assertFalse(edit.backup.exists())
    }

    @Test
    fun trimRejectsBadRanges() {
        val id = importSample()
        val original = fileOf(id).readBytes()
        runBlocking {
            assertIs<IllegalArgumentException>(repository.trim(id, 5, 5).exceptionOrNull())
            assertIs<IllegalArgumentException>(repository.trim(id, 0, 20).exceptionOrNull())
        }
        assertContentEquals(original, fileOf(id).readBytes())
    }

    @Test
    fun commitEditDropsTheBackup() {
        val id = importSample()
        val edit = runBlocking { repository.trim(id, 0, 9).getOrThrow() }
        repository.commitEdit(edit)
        waitFor(message = "backup deleted") { !edit.backup.exists() }
    }

    @Test
    fun duplicateCopiesFileAndRow() {
        val id = importSample()
        runBlocking { repository.setVisible(id, false) }

        val copy = runBlocking { repository.duplicate(id).getOrThrow() }
        assertNotEquals(id, copy)
        val entity = row(copy)
        assertEquals("Test ride (copy)", entity.trackName)
        assertTrue(entity.visible)
        assertNotEquals(row(id).location, entity.location)
        assertEquals(row(id).summary, entity.summary)
        assertEquals("Test ride (copy)", parsed(copy).name)
        assertEquals(20, parsed(copy).points.size)
    }

    @Test
    fun deleteIsHiddenUntilCommittedOrUndone() {
        val id = importSample()
        val file = fileOf(id)

        repository.deleteLater(listOf(id))
        waitFor(message = "hidden") { repository.tracks.first().none { it.id == id } }
        repository.undoDelete(listOf(id))
        waitFor(message = "shown again") { repository.tracks.first().any { it.id == id } }

        repository.deleteLater(listOf(id))
        repository.commitDelete(listOf(id))
        waitFor(message = "row deleted") { dao.byId(id) == null }
        waitFor(message = "file deleted") { !file.exists() }
    }

    @Test
    fun summariseOlderRowsFillsVersion1Rows() {
        val id = importSample()
        val summary = row(id).summary
        runBlocking {
            dao.setSummary(SummaryUpdate(id, null, summary.copy(pointCount = -1, ascentMeters = 0.0, northLatitude = 0.0)))
        }
        assertFalse(row(id).summarised)

        repository.summariseOlderRows()
        waitFor(message = "summarised") { dao.byId(id)!!.summarised }
        assertEquals(summary, row(id).summary)
        assertNotNull(row(id).startedAtEpochMillis)
    }

    @Test
    fun saveRecordingIndexes() {
        val points = TrackPointsBuilder().apply {
            val start = Instant.parse("2024-05-04T18:00:00Z")
            repeat(5) { add(TrackPoint(51.5 + it * 0.001, -0.1, 20.0, start.plusSeconds(10L * it))) }
        }.build()
        val id = runBlocking { repository.saveRecording(Track(name = null, points = points)).getOrThrow() }
        val entity = row(id)
        assertTrue(entity.location.startsWith("recordings/"))
        // Unnamed, in the row and the file.
        assertNull(entity.trackName)
        assertNull(parsed(id).name)
        assertEquals(5, entity.summary.pointCount)

        val named = runBlocking { repository.saveRecording(Track(name = "Commute", points = points)).getOrThrow() }
        assertEquals("Commute", row(named).trackName)
        assertNotEquals(row(id).location, row(named).location)
    }

    @Test
    fun colourWrapsAndSizesAreFileLengths() {
        val id = importSample()
        runBlocking {
            repository.setColor(id, TrackEntity.PALETTE_SIZE + 2)
            assertEquals(2, row(id).colorIndex)
            repository.setVisible(listOf(id), false)
            assertFalse(row(id).visible)
            assertEquals(mapOf(id to fileOf(id).length()), repository.fileSizes(listOf(row(id))))
        }
    }
}
