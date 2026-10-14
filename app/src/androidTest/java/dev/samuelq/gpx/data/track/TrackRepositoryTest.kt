package dev.samuelq.gpx.data.track

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.data.db.GpxDatabase
import dev.samuelq.gpx.data.db.TrackDao
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.gpx.GpxParser
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.fixture
import dev.samuelq.gpx.sampleGpx
import dev.samuelq.gpx.targetContext
import dev.samuelq.gpx.waitFor
import java.io.File
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        repository = TrackRepository(targetContext, dao, SettingsRepository(targetContext), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun importSample(content: String = sampleGpx(), name: String = "sample-${System.nanoTime()}.gpx"): Long =
        runBlocking { repository.import(Uri.fromFile(fixture(name, content))).getOrThrow() }

    private fun row(id: Long): TrackEntity = runBlocking { dao.byId(id)!! }

    private fun fileOf(id: Long): File = TrackFiles.file(targetContext, id)

    private fun parsed(id: Long): Track = fileOf(id).inputStream().use(GpxParser()::parse)

    /** The track as it leaves the app. */
    private fun shared(id: Long): Track =
        runBlocking { repository.fileToShare(row(id)) }.inputStream().use(GpxParser()::parse)

    private fun loaded(id: Long): LoadedTrack = runBlocking { repository.geometry(id).getOrThrow() }

    private fun trackFiles(): Set<String> = TrackFiles.dir(targetContext).list().orEmpty().toSet()

    private fun stagedFiles(): Set<String> = TrackFiles.stagingDir(targetContext).list().orEmpty().toSet()

    @Test
    fun importIndexesTheCopy() {
        val id = importSample()
        val entity = row(id)

        assertEquals("$id.gpx", fileOf(id).name)
        assertTrue(fileOf(id).exists())
        assertTrue(stagedFiles().isEmpty())
        assertEquals("Test ride", entity.trackName)
        assertTrue(entity.distanceMeters > 200)
        assertEquals(38.0, entity.totalSeconds)
        assertEquals(Instant.parse("2024-05-04T09:00:00Z").toEpochMilli(), entity.startedAtEpochMillis)

        val track = loaded(id)
        assertEquals(20, track.track.points.size)
        assertEquals(1, track.track.waypoints.size)
    }

    @Test
    fun anUnnamedImportIsNamedAfterItsFile() {
        val id = importSample(sampleGpx(name = null), name = "Morning loop.gpx")
        assertEquals("Morning loop", row(id).trackName)
    }

    @Test
    fun reimportingIdenticalSharedFileReusesTheRow() {
        val name = "shared-${System.nanoTime()}.gpx"
        val file = fixture(name, sampleGpx())
        runBlocking {
            val received = repository.receive(Uri.fromFile(file)).getOrThrow()
            assertTrue(received.isNew)
            val first = received.id
            // A rename leaves the file as it came, so it still matches.
            repository.rename(first, "Renamed").getOrThrow()
            val before = trackFiles()
            val again = repository.receive(Uri.fromFile(file)).getOrThrow()
            assertEquals(first, again.id)
            assertFalse(again.isNew)
            assertEquals(before, trackFiles())

            // A deliberate import always copies.
            val copy = repository.import(Uri.fromFile(file)).getOrThrow()
            assertNotEquals(first, copy)
        }
    }

    @Test
    fun failedImportsLeaveNothingBehind() {
        val before = trackFiles()
        runBlocking {
            val invalid = repository.import(
                Uri.fromFile(fixture("bad.gpx", "<gpx><trk><trkseg><trkpt")),
            ).exceptionOrNull()
            assertIs<TrackLoadException.Invalid>(invalid)

            // Refused by Android's own parser, not just the tests' kxml2.
            val doctype = repository.import(
                Uri.fromFile(
                    fixture("doctype.gpx", sampleGpx().replaceFirst("<gpx", "<!DOCTYPE gpx [<!ENTITY x \"y\">]><gpx")),
                ),
            ).exceptionOrNull()
            assertIs<TrackLoadException.Invalid>(doctype)

            val waypointsOnly = repository.import(
                Uri.fromFile(fixture("empty.gpx", sampleGpx(points = 0))),
            ).exceptionOrNull()
            assertIs<TrackLoadException.Empty>(waypointsOnly)

            val missing = repository.import(Uri.fromFile(File(targetContext.cacheDir, "missing.gpx"))).exceptionOrNull()
            assertIs<TrackLoadException.Unreadable>(missing)
        }
        assertEquals(before, trackFiles())
        assertTrue(stagedFiles().isEmpty())
        assertTrue(runBlocking { repository.tracks.first() }.isEmpty())
    }

    /** Refused while copying, and up front where the provider gives a size. */
    @Test
    fun anImportPastTheLimitIsRefusedAndLeavesNothing() {
        // Under the shared root, so the FileProvider can serve it with a size.
        val file = File(targetContext.cacheDir, "shared/big-${System.nanoTime()}.gpx").apply {
            parentFile!!.mkdirs()
            writeText(sampleGpx())
        }
        val small = TrackRepository(
            targetContext,
            dao,
            SettingsRepository(targetContext),
            scope,
            maxImportBytes = file.length() - 1,
        )
        try {
            runBlocking {
                // A file URI has no size to give; a content URI does.
                val sized = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
                for (uri in listOf(Uri.fromFile(file), sized)) {
                    assertIs<TrackLoadException.TooLarge>(small.import(uri).exceptionOrNull())
                }
                val fits = small.import(Uri.fromFile(fixture("fits.gpx", sampleGpx(points = 2)))).getOrThrow()
                // This test's database alone: the refused ones added no row.
                assertEquals(listOf(fits), small.tracks.first().map { it.id })
            }
        } finally {
            file.delete()
        }
        assertTrue(stagedFiles().isEmpty())
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
    fun renameIsTheRowsAndGoesOutWithTheFile() {
        val id = importSample()
        val original = fileOf(id).readBytes()
        runBlocking { repository.rename(id, "  Evening loop ").getOrThrow() }
        assertEquals("Evening loop", row(id).trackName)
        assertEquals("Evening loop", shared(id).name)
        assertContentEquals(original, fileOf(id).readBytes())

        // Cleared: titled by its start, and no name goes out.
        runBlocking { repository.rename(id, " ").getOrThrow() }
        assertNull(row(id).trackName)
        assertTrue(row(id).isTitledByStart)
        assertNull(shared(id).name)
        assertEquals(20, shared(id).points.size)
    }

    @Test
    fun untimedImportsHaveNoStartAndKeepAName() {
        // One point without a time is enough.
        val partly = importSample(sampleGpx().replaceFirst(Regex("<time>[^<]*</time>"), ""))
        assertNull(row(partly).startedAtEpochMillis)
        assertEquals(0.0, row(partly).totalSeconds)

        val unnamed = sampleGpx(name = null).replace(Regex("<time>[^<]*</time>"), "")
        val id = importSample(unnamed, name = "Planned.gpx")
        assertEquals("Planned", row(id).trackName)
        // Blank would leave it no title, so it's ignored.
        runBlocking { repository.rename(id, " ").getOrThrow() }
        assertEquals("Planned", row(id).trackName)
        assertEquals("Planned", row(id).title)
        // Kept as it came.
        assertEquals(unnamed, fileOf(id).readText())
    }

    @Test
    fun routesAreStoredAsTracks() {
        val route = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1"
                xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
              <wpt lat="51.5" lon="-0.1"><name>Start</name></wpt>
              <rte>
                <name>Loop</name>
                <type>Hiking</type>
                <extensions><gpxx:RouteExtension><gpxx:DisplayColor>DarkBlue</gpxx:DisplayColor></gpxx:RouteExtension></extensions>
                <rtept lat="51.5" lon="-0.1"><ele>10</ele></rtept>
                <rtept lat="51.501" lon="-0.1"/>
              </rte>
              <rte><rtept lat="51.502" lon="-0.1"/></rte>
            </gpx>"""
        val id = importSample(route)
        val row = row(id)
        assertEquals("Loop", row.trackName)
        assertEquals("Hiking", row.category)
        assertEquals(RouteColor.Blue, row.color)
        assertNull(row.startedAtEpochMillis)

        val stored = fileOf(id).readText()
        assertFalse("<rte" in stored)
        // Written as a recording is, without indentation.
        assertFalse("\n " in stored)
        val track = parsed(id)
        assertFalse(track.isRoute)
        assertEquals(3, track.points.size)
        assertEquals(2, track.points.segmentCount)
        assertEquals("Start", track.waypoints.single().name)
        // The row's name goes out, as any track's.
        assertEquals("Loop", shared(id).name)
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
        runBlocking { repository.rename(first, name = null, category = "Hikes $first").getOrThrow() }
        runBlocking { repository.rename(second, name = null, category = "HIKES $first").getOrThrow() }
        assertEquals("Hikes $first", row(second).category)
        assertEquals("Hikes $first", shared(second).type)
        // Alone in it, a track can respell its own.
        val alone = importSample()
        runBlocking { repository.rename(alone, name = null, category = "solo $alone").getOrThrow() }
        runBlocking { repository.rename(alone, name = null, category = "Solo $alone").getOrThrow() }
        assertEquals("Solo $alone", row(alone).category)
    }

    @Test
    fun categoryIsSetAlone() {
        val id = importSample(sampleGpx().replace("<trkseg>", "<type>Rides</type><trkseg>"))
        runBlocking { repository.rename(id, name = null, category = " Hikes ").getOrThrow() }
        assertEquals("Hikes", row(id).category)
        assertEquals("Hikes", shared(id).type)
        assertEquals("Test ride", row(id).trackName)
        assertEquals("Rides", parsed(id).type)

        runBlocking { repository.rename(id, name = null, category = " ").getOrThrow() }
        assertNull(row(id).category)
        assertNull(shared(id).type)
    }

    @Test
    fun undoingATrimKeepsARenameSince() {
        val id = importSample()
        val original = fileOf(id).readBytes()
        val edit = runBlocking { repository.trim(id, 5, 14).getOrThrow() }
        runBlocking { repository.rename(id, "Renamed").getOrThrow() }

        runBlocking { repository.undoEdit(edit).getOrThrow() }
        assertEquals("Renamed", row(id).trackName)
        assertEquals(20, loaded(id).track.points.size)
        assertContentEquals(original, fileOf(id).readBytes())
    }

    @Test
    fun trimLeavesNothingUnseenFromBeforeTheCut() {
        val unseen = """<metadata><time>2024-05-04T09:00:00Z</time><author><name>Home</name></author></metadata>""" +
            """<rte><rtept lat="60" lon="10"/></rte>"""
        val id = importSample(
            sampleGpx(waypointAt = 8)
                .replace("<trk>", "$unseen<trk>")
                .replace("<trkseg>", "<extensions><device>Watch</device></extensions><trkseg>"),
        )

        runBlocking { repository.trim(id, 5, 14).getOrThrow() }
        val track = parsed(id)
        assertEquals(10, track.points.size)
        assertEquals(51.5005, track.points.latitude(0), 1e-9)
        assertEquals(listOf("Cafe"), track.waypoints.map { it.name })
        val text = fileOf(id).readText()
        listOf("""lat="60"""", "Home", "Watch", "09:00:00Z").forEach { assertFalse(it in text, text) }
        assertTrue("<time>2024-05-04T09:00:10Z</time></metadata>" in text.replace(Regex(">\\s+<"), "><"), text)
        // Still named on the way out, from the row.
        assertEquals("Test ride", shared(id).name)
    }

    @Test
    fun trimAndUndo() {
        val id = importSample()
        val original = fileOf(id).readBytes()

        val before = row(id).summary
        val edit = runBlocking { repository.trim(id, 5, 14).getOrThrow() }
        // Ten points two seconds apart, from the sixth.
        assertEquals(18.0, row(id).totalSeconds)
        assertEquals(before.startedAtEpochMillis!! + 10_000, row(id).startedAtEpochMillis)
        assertEquals(10, loaded(id).track.points.size)
        // The waypoint by point 15 is outside what's kept.
        assertTrue(loaded(id).track.waypoints.isEmpty())
        assertTrue(edit.backup.exists())

        runBlocking { repository.undoEdit(edit).getOrThrow() }
        assertContentEquals(original, fileOf(id).readBytes())
        assertEquals(before, row(id).summary)
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

    /** As a crash mid-delete, or an undo after one, leaves: a file without a row. */
    @Test
    fun launchSweepsFilesWithoutARow() {
        val kept = importSample()
        val orphan = TrackFiles.file(targetContext, 9_000_000).apply {
            writeText(sampleGpx())
            setLastModified(0)
        }
        val staged = File(TrackFiles.stagingDir(targetContext), "leftover.gpx").apply {
            writeText(sampleGpx())
            setLastModified(0)
        }
        // Written after the launch, as by an import from the launching intent still moving in.
        val fresh = TrackFiles.file(targetContext, 9_000_001).apply {
            writeText(sampleGpx())
            setLastModified(System.currentTimeMillis() + 60_000)
        }
        try {
            repository.purgeAtLaunch()
            waitFor(message = "swept") { !orphan.exists() && !staged.exists() }
            assertTrue(fileOf(kept).exists())
            assertTrue(fresh.exists())
        } finally {
            fresh.delete()
        }
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
    fun saveRecordingIndexes() {
        val points = TrackPointsBuilder().apply {
            val start = Instant.parse("2024-05-04T18:00:00Z")
            repeat(5) { add(TrackPoint(51.5 + it * 0.001, -0.1, 20.0, start.plusSeconds(10L * it))) }
        }.build()
        val id = runBlocking { repository.saveRecording(Track(name = null, points = points)).getOrThrow() }
        val entity = row(id)
        assertEquals("$id.gpx", fileOf(id).name)
        // Named only in the row, never the file.
        assertNull(entity.trackName)
        assertNull(parsed(id).name)
        assertEquals(40.0, entity.totalSeconds)
        assertTrue(stagedFiles().isEmpty())

        val named = runBlocking {
            repository.saveRecording(Track(name = "Commute", type = "Rides", points = points)).getOrThrow()
        }
        assertEquals("Commute", row(named).trackName)
        assertNull(parsed(named).name)
        assertNull(parsed(named).type)
        assertEquals("Commute", shared(named).name)
        assertEquals("Rides", shared(named).type)
        assertNotEquals(fileOf(id), fileOf(named))
        assertEquals("Rides", runBlocking { repository.lastRecordingCategory.first() })
    }

    @Test
    fun colourWrapsAndSizesAreFileLengths() {
        val id = importSample()
        runBlocking {
            repository.setColor(id, RouteColor.entries.size + 2)
            assertEquals(RouteColor.Green, row(id).color)
            repository.setVisible(listOf(id), false)
            assertFalse(row(id).visible)
            assertEquals(mapOf(id to fileOf(id).length()), repository.fileSizes(listOf(row(id))))
        }
    }
}
