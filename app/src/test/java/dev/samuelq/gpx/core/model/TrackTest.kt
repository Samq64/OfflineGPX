package dev.samuelq.gpx.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackTest {

    private fun point(longitude: Double) = TrackPoint(latitude = 0.0, longitude = longitude)

    /** Two segments, 0..2 and 3..5 - a signal-loss gap sits between index 2 and 3. */
    private fun twoSegmentTrack(): Track = Track(
        name = "test",
        description = null,
        segments = listOf(
            TrackSegment((0..2).map { point(it.toDouble()) }),
            TrackSegment((3..5).map { point(it.toDouble()) }),
        ),
    )

    @Test
    fun `slice within one segment keeps that segment alone`() {
        val sliced = twoSegmentTrack().slice(1..2)

        assertEquals(1, sliced.segments.size)
        assertEquals(listOf(1.0, 2.0), sliced.points.map { it.longitude })
    }

    @Test
    fun `slice spanning the gap keeps both segments, still two segments`() {
        val sliced = twoSegmentTrack().slice(2..3)

        // The gap is not healed - two segments survive, not one merged run.
        assertEquals(2, sliced.segments.size)
        assertEquals(listOf(2.0, 3.0), sliced.points.map { it.longitude })
        assertEquals(intArrayOf(0, 1).toList(), sliced.segmentStartIndices.toList())
    }

    @Test
    fun `slice entirely inside the second segment drops the first`() {
        val sliced = twoSegmentTrack().slice(4..5)

        assertEquals(1, sliced.segments.size)
        assertEquals(listOf(4.0, 5.0), sliced.points.map { it.longitude })
    }

    @Test
    fun `slice covering everything is unchanged`() {
        val original = twoSegmentTrack()
        val sliced = original.slice(0..original.points.lastIndex)

        assertEquals(original.points, sliced.points)
        assertEquals(2, sliced.segments.size)
    }

    @Test
    fun `slice to a single point keeps one point`() {
        val sliced = twoSegmentTrack().slice(0..0)

        assertEquals(listOf(0.0), sliced.points.map { it.longitude })
    }

    @Test
    fun `slice past the end of the track is empty`() {
        val sliced = twoSegmentTrack().slice(6..10)

        assertTrue(sliced.isEmpty)
    }

    @Test
    fun `slice keeps the track's name and description`() {
        val track = Track(name = "Morning ride", description = "notes", segments = listOf(TrackSegment(listOf(point(0.0), point(1.0)))))

        val sliced = track.slice(0..0)

        assertEquals("Morning ride", sliced.name)
        assertEquals("notes", sliced.description)
    }
}
