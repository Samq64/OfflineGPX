package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingSessionTest {

    private val origin = Instant.parse("2026-09-18T09:00:00Z")
    private var now = 0L

    private fun session() = RecordingSession(
        maxAccuracyMeters = 20.0,
        clock = { now },
    )

    private fun fix(second: Long, metersNorth: Double, accuracy: Double = 5.0) = TrackPoint(
        latitude = 51.5 + metersNorth / 111_320.0,
        longitude = -0.1,
        time = origin.plusSeconds(second),
        accuracyMeters = accuracy,
    )

    @Test
    fun `accepted fixes accumulate distance and carry their accuracy`() {
        val session = session()
        session.onFix(fix(0, 0.0))
        val point = session.onFix(fix(1, 10.0, accuracy = 4.0))

        assertEquals(4.0, point?.accuracyMeters)
        assertEquals(10.0, session.distanceMeters, 0.1)
        assertEquals(2, session.state().pointCount)
    }

    @Test
    fun `lost signal breaks the line and adds no distance, as the saved track's analysis won't`() {
        val session = session()
        session.onFix(fix(0, 0.0))
        session.onFix(fix(1, 10.0))
        // Over the analysis's 30 s floor since the last point, 500 m on.
        session.onFix(fix(60, 510.0))
        session.onFix(fix(61, 520.0))

        assertEquals(20.0, session.distanceMeters, 0.1)
        assertContentEquals(intArrayOf(0, 2), session.trace().segmentStarts())
    }

    @Test
    fun `a rejected fix is not logged but its accuracy is shown`() {
        val session = session()
        assertNull(session.onFix(fix(0, 0.0, accuracy = 50.0)))
        val state = session.state()
        assertEquals(50.0, state.accuracyMeters)
        assertEquals(20.0, state.accuracyLimitMeters)
        assertNull(state.lastPoint)
    }

    @Test
    fun `duration starts at the first accepted point`() {
        val session = session()
        now = 1_000
        session.onFix(fix(0, 0.0, accuracy = 50.0))
        assertEquals(0.0, session.totalSeconds)

        now = 2_000
        session.onFix(fix(1, 0.0))
        now = 7_000
        assertEquals(5.0, session.totalSeconds)
    }

    @Test
    fun `a pause starts a new trace segment and does not bridge the gap`() {
        val session = session()
        session.onFix(fix(0, 0.0))
        session.onFix(fix(1, 10.0))
        session.pause(origin.plusSeconds(1))
        assertTrue(session.paused)
        assertNull(session.onFix(fix(2, 20.0)), "paused fixes are dropped")

        assertTrue(session.resume())
        session.onFix(fix(60, 1_000.0))
        session.onFix(fix(61, 1_010.0))

        val trace = session.trace()
        assertEquals(4, trace.size)
        assertContentEquals(intArrayOf(0, 2), trace.segmentStarts())
        assertEquals(20.0, session.distanceMeters, 0.1)
    }

    @Test
    fun `the trace is due on the first point, then every few points`() {
        val session = session()
        assertFalse(session.traceDue)
        session.onFix(fix(0, 0.0))
        assertTrue(session.traceDue)
        session.trace()
        repeat(4) { session.onFix(fix(it + 1L, (it + 1) * 10.0)) }
        assertFalse(session.traceDue)
        session.onFix(fix(5, 50.0))
        assertTrue(session.traceDue)
        session.trace()
        assertFalse(session.traceDue)
    }

    @Test
    fun `a waypoint needs a position and trims its description`() {
        val session = session()
        val at = origin.plusSeconds(30)
        assertNull(session.addWaypoint("early", at))

        session.onFix(fix(0, 0.0))
        val waypoint = assertNotNull(session.addWaypoint("  summit ", at))
        assertEquals("summit", waypoint.description)
        assertEquals(at, waypoint.point.time)
        assertNull(session.addWaypoint("   ", at)?.description)
        assertEquals(2, session.state().waypoints.size)
    }

    @Test
    fun `a waypoint can be added while paused, where the ride stopped`() {
        val session = session()
        session.onFix(fix(0, 0.0))
        session.onFix(fix(1, 10.0))
        session.pause(origin.plusSeconds(1))

        val waypoint = assertNotNull(session.addWaypoint("lunch", origin.plusSeconds(90)))
        assertEquals(session.state().lastPoint?.latitude, waypoint.point.latitude)
    }

    @Test
    fun `a pause ends the segment and elapsed time at the moment it was tapped`() {
        val session = session()
        now = 0
        session.onFix(fix(0, 0.0))
        now = 30_000
        val closing = assertNotNull(session.pause(origin.plusSeconds(30)))
        assertEquals(origin.plusSeconds(30), closing.time)
        assertEquals(0.0, closing.latitude - fix(0, 0.0).latitude)
        now = 90_000
        assertEquals(30.0, session.totalSeconds)
        assertEquals(2, session.trace().size)
    }

    @Test
    fun `stopping holds fixes and elapsed time until it is answered`() {
        val session = session()
        now = 0
        session.onFix(fix(0, 0.0))
        now = 20_000
        assertEquals(origin.plusSeconds(20), session.hold(origin.plusSeconds(20))?.time)
        assertNull(session.onFix(fix(25, 50.0)), "fixes wait on the answer")
        now = 60_000
        assertEquals(20.0, session.totalSeconds)

        session.release()
        now = 61_000
        assertNotNull(session.onFix(fix(61, 50.0)))
        assertEquals(61.0, session.totalSeconds)
    }

    @Test
    fun `nothing closes the log while paused`() {
        val session = session()
        session.onFix(fix(0, 0.0))
        session.pause(origin.plusSeconds(1))
        assertNull(session.hold(origin.plusSeconds(10)))
    }
}
