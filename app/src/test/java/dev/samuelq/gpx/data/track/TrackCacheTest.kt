package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.Waypoint
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackCacheTest {

    private val at = Instant.parse("2026-05-01T08:00:00Z")
    private val stamp = TrackCache.Stamp(length = 1234, modified = 5678)

    private val track = Track(
        name = "Ignored",
        points = TrackPoints.of(
            listOf(TrackPoint(47.1, 8.5, 430.5, at, 4.0), TrackPoint(47.2, 8.6)),
            listOf(TrackPoint(47.3, 8.7, time = at.plusSeconds(60))),
        ),
        description = "Über the hill",
        waypoints = listOf(Waypoint(TrackPoint(47.15, 8.55, time = at), "Café"), Waypoint(TrackPoint(1.0, 2.0))),
    )

    @Test
    fun `a track survives the round trip, all but its name`() {
        val decoded = assertNotNull(TrackCache.decode(ByteBuffer.wrap(TrackCache.encode(track, stamp)), stamp))

        assertNull(decoded.name)
        assertEquals(track.description, decoded.description)
        assertEquals(track.waypoints, decoded.waypoints)
        assertEquals(track.points.segmentStarts().toList(), decoded.points.segmentStarts().toList())
        assertEquals(track.points.indices.map(track.points::get), decoded.points.indices.map(decoded.points::get))
    }

    @Test
    fun `another stamp is a miss`() {
        val bytes = TrackCache.encode(track, stamp)
        assertNull(TrackCache.decode(ByteBuffer.wrap(bytes), stamp.copy(modified = 9999)))
    }

    @Test
    fun `a truncated entry is rejected rather than read short`() {
        val bytes = TrackCache.encode(track, stamp)
        assertFails { TrackCache.decode(ByteBuffer.wrap(bytes.copyOf(bytes.size - 3)), stamp) }
    }
}
