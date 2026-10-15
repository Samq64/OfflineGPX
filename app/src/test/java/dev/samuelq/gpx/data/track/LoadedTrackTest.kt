package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadedTrackTest {

    private val points = List(6) { TrackPoint(it * 0.001, 0.0, null, null) }
    private val near1 = Waypoint(TrackPoint(0.001, 0.00001, null, null), "near 1")
    private val near4 = Waypoint(TrackPoint(0.004, 0.00001, null, null), "near 4")
    private val loaded = Track(name = null, points = TrackPoints.of(points), waypoints = listOf(near4, near1)).let {
        LoadedTrack(1, it, TrackAnalyzer.analyze(it))
    }

    @Test
    fun `a trim keeps the waypoints by the points it keeps, in file order`() {
        assertEquals(listOf(near1), loaded.waypointsKeptBy(0..2))
        assertEquals(listOf(near4), loaded.waypointsCutBy(0..2))
        assertEquals(listOf(near4, near1), loaded.waypointsKeptBy(1..4))
        assertEquals(emptyList(), loaded.waypointsCutBy(1..4))
    }
}
