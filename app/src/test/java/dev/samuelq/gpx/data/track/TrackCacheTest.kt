package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.Waypoint
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        waypoints = listOf(Waypoint(TrackPoint(47.15, 8.55, 1200.0, at), "Café"), Waypoint(TrackPoint(1.0, 2.0))),
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

    private val dir = Files.createTempDirectory("cache").toFile().apply { deleteOnExit() }
    private val cache = TrackCache(dir)
    private val source = File(dir, "track.gpx").apply {
        writeText("<gpx/>")
        setLastModified(1_000_000)
    }
    private val entry = File(dir, "7.bin")

    @Test
    fun `a written entry reads back while its source is unchanged`() {
        assertNull(cache.read(7, source), "nothing cached yet")
        cache.write(7, source, track)

        val read = assertNotNull(cache.read(7, source))
        assertEquals(track.waypoints, read.waypoints)
        assertEquals(track.points.size, read.points.size)
    }

    @Test
    fun `an edited source makes the entry stale`() {
        cache.write(7, source, track)
        source.writeText("<gpx><trk/></gpx>")
        assertNull(cache.read(7, source))
    }

    @Test
    fun `restamping keeps an entry valid after a rewrite it doesn't hold`() {
        cache.write(7, source, track)
        source.writeText("<gpx><metadata/></gpx>")
        source.setLastModified(2_000_000)
        cache.restamp(7, source)
        assertEquals(track.description, cache.read(7, source)?.description)

        cache.restamp(8, source)
        assertFalse(File(dir, "8.bin").exists(), "no entry, nothing to restamp")
    }

    @Test
    fun `an entry too short to restamp is dropped`() {
        entry.writeBytes(ByteArray(4))
        cache.restamp(7, source)
        assertFalse(entry.exists())
    }

    /** As a crash mid-write or an older format would leave. */
    @Test
    fun `an unreadable entry is a miss and is deleted`() {
        cache.write(7, source, track)
        entry.writeBytes(entry.readBytes().copyOf(40))
        assertNull(cache.read(7, source))
        assertFalse(entry.exists())
    }

    @Test
    fun `a failed write is not an error`() {
        val blocked = TrackCache(source) // a file, not a directory
        blocked.write(7, source, track)
        assertNull(blocked.read(7, source))
    }

    @Test
    fun `delete removes the entry`() {
        cache.write(7, source, track)
        assertTrue(entry.exists())
        cache.delete(7)
        assertNull(cache.read(7, source))
    }

    @Test
    fun `another format or version is rejected`() {
        val bytes = TrackCache.encode(track, stamp)
        val badMagic = bytes.copyOf().also { it[0] = 0 }
        assertFails { TrackCache.decode(ByteBuffer.wrap(badMagic), stamp) }
        val badVersion = bytes.copyOf().also { it[7] = 99 }
        assertFails { TrackCache.decode(ByteBuffer.wrap(badVersion), stamp) }
    }

    @Test
    fun `a string longer than the entry is rejected`() {
        val bytes = TrackCache.encode(track, stamp)
        // The description's length follows the 24-byte header.
        ByteBuffer.wrap(bytes).putInt(24, Int.MAX_VALUE)
        assertFails { TrackCache.decode(ByteBuffer.wrap(bytes), stamp) }
    }

    @Test
    fun `an empty track with no description round-trips`() {
        val empty = Track(name = null, points = TrackPoints.EMPTY)
        val decoded = assertNotNull(TrackCache.decode(ByteBuffer.wrap(TrackCache.encode(empty, stamp)), stamp))
        assertEquals(0, decoded.points.size)
        assertNull(decoded.description)
        assertTrue(decoded.waypoints.isEmpty())
    }
}
