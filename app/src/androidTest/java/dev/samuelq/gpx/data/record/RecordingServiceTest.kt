package dev.samuelq.gpx.data.record

import android.Manifest
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.container
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.shell
import dev.samuelq.gpx.targetContext
import dev.samuelq.gpx.waitFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real service with fixes from a GPS test provider. The activity is up so the
 * location foreground service may start, as it would from the record button.
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    private val manager = targetContext.getSystemService<LocationManager>()!!
    private val controller get() = container.recordingController
    private var scenario: ActivityScenario<MainActivity>? = null
    private var latitude = 51.5

    @Before
    fun setUp() {
        shell("appops set ${targetContext.packageName} android:mock_location allow")
        shell("settings put secure location_mode 3")
        @Suppress("DEPRECATION")
        manager.addTestProvider(
            LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
        )
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (controller.state.value is RecordingState.Active) {
            controller.discard("")
            waitFor(message = "idle") { controller.state.value == RecordingState.Idle }
        }
        scenario?.close()
        runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
    }

    /** Steps north ~11 m per fix, a second apart. */
    private fun pushFixes(count: Int) = repeat(count) {
        latitude += 0.0001
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = this@RecordingServiceTest.latitude
            longitude = -0.1
            altitude = 50.0
            accuracy = 3f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        Thread.sleep(1_000)
    }

    private fun active(): RecordingState.Active? = controller.state.value as? RecordingState.Active

    private fun recordings(): List<TrackEntity> = runBlocking {
        container.trackRepository.tracks.first().filter { it.location.startsWith("recordings/") }
    }

    @Test
    fun recordPauseWaypointAndSave() {
        val before = recordings().map { it.id }.toSet()

        controller.start()
        waitFor(message = "active") { active() != null }
        pushFixes(4)
        waitFor(message = "first points") { (active()?.pointCount ?: 0) >= 3 }

        controller.addWaypoint("Bench")
        waitFor(message = "waypoint") { active()?.waypoints?.size == 1 }

        controller.pause()
        waitFor(message = "paused") { active()?.paused == true }
        val pausedCount = active()!!.pointCount
        pushFixes(2)
        assertEquals(pausedCount, active()!!.pointCount)

        controller.resume()
        waitFor(message = "resumed") { active()?.paused == false }
        pushFixes(4)
        waitFor(message = "more points") { active()!!.pointCount > pausedCount }

        controller.requestStop()
        waitFor(message = "stop asked") { controller.stopRequested.value }
        controller.stop("Service ride")
        waitFor(message = "idle") { controller.state.value == RecordingState.Idle }

        val saved = recordings().filter { it.id !in before }
        assertEquals(1, saved.size)
        assertEquals("Service ride", saved.single().trackName)
        val track = runBlocking { container.trackRepository.geometry(saved.single().id).getOrThrow() }.track
        // The pause is a segment break.
        assertEquals(2, track.points.segmentCount)
        assertEquals("Bench", track.waypoints.single().name)
        // The live log is consumed by the save.
        assertTrue(!container.recordingRecovery.liveLog.exists() || container.recordingRecovery.liveLog.length() == 0L)
    }

    @Test
    fun tooShortARideIsNotSaved() {
        val before = recordings().size
        controller.start()
        waitFor(message = "active") { active() != null }
        controller.stop("")
        waitFor(message = "idle") { controller.state.value == RecordingState.Idle }
        assertEquals(before, recordings().size)
    }

    @Test
    fun discardIsSetAsideForUndo() {
        val recovery = container.recordingRecovery
        controller.start()
        waitFor(message = "active") { active() != null }
        pushFixes(4)
        waitFor(message = "points") { (active()?.pointCount ?: 0) >= 3 }

        val before = recordings().size
        controller.discard("Undone")
        waitFor(message = "idle") { controller.state.value == RecordingState.Idle }
        assertEquals(before, recordings().size)

        // The event may be taken by the UI, so set aside again from a fresh log instead.
        val log = java.io.File(targetContext.cacheDir, "discard.wal")
        RecordingWal.open(log).use { wal -> walkNorth(20).forEach(wal::append) }
        val aside = assertNotNull(runBlocking { recovery.setAside(log, "Undone") })
        val id = runBlocking { recovery.restore(aside).getOrThrow() }
        // The shared list may replay its previous value first.
        waitFor(message = "restored") {
            container.trackRepository.tracks.first().any { it.id == id && it.trackName == "Undone" }
        }
    }

    @Test
    fun abandonedLogIsRecovered() {
        val recovery = container.recordingRecovery
        RecordingWal.open(recovery.liveLog).use { wal ->
            val points = walkNorth(10)
            points.take(5).forEach(wal::append)
            wal.appendBreak()
            points.drop(5).forEach(wal::append)
        }
        // A crash mid-write leaves a torn line.
        recovery.liveLog.appendText("1714813200000,51.5")

        runBlocking {
            assertTrue(recovery.claim())
            val abandoned = recovery.abandoned()
            val ride = abandoned.single { it.track.points.size == 10 }
            assertEquals(2, ride.track.points.segmentCount)
            assertTrue(ride.defaultName.isNotBlank())

            val id = recovery.save(ride, "Recovered").getOrThrow()
            val saved = container.trackRepository.geometry(id).getOrThrow()
            assertEquals(10, saved.track.points.size)
            assertTrue(recovery.abandoned().none { it.track.points.size == 10 })
        }
    }

    @Test
    fun uselessLogsAreDropped() {
        val recovery = container.recordingRecovery
        RecordingWal.open(recovery.liveLog).use { wal -> wal.append(walkNorth(1).single()) }
        runBlocking {
            assertTrue(recovery.claim())
            assertTrue(recovery.abandoned().isEmpty())
            val log = java.io.File(targetContext.cacheDir, "short.wal").apply { writeText("") }
            assertEquals(null, recovery.setAside(log, "x"))
            assertTrue(!log.exists())
        }
    }

    @Test
    fun restoreOfAMissingFileFails() {
        val missing = DiscardedRecording(java.io.File(targetContext.cacheDir, "gone.wal"), "x")
        assertIs<java.io.IOException>(runBlocking { container.recordingRecovery.restore(missing) }.exceptionOrNull())
    }

    private fun walkNorth(count: Int): List<TrackPoint> {
        val start = Instant.now().minusSeconds(count * 2L)
        return List(count) { TrackPoint(51.5 + it * 0.0002, -0.1, 30.0, start.plusSeconds(2L * it), 4.0) }
    }
}
