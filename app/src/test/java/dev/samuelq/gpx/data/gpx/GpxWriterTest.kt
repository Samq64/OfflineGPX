package dev.samuelq.gpx.data.gpx

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.core.model.Waypoint
import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpxWriterTest {

    private val writer = GpxWriter { KXmlSerializer() }
    private val parser = GpxParser { KXmlParser() }

    private val track = Track(
        name = "Ride\u0001 home",
        points = TrackPointsBuilder().apply {
            startSegment()
            add(TrackPoint(47.1, 8.6))
            add(TrackPoint(47.3, 8.5))
        }.build(),
        waypoints = listOf(Waypoint(TrackPoint(47.3, 8.5), "Summit")),
    )

    private fun write(): String {
        val out = ByteArrayOutputStream()
        writer.write(track, out)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `labels waypoints with name and bounds the points`() {
        val xml = write()
        assertTrue("<name>Summit</name>" in xml, xml)
        assertTrue(
            Regex("""<bounds minlat="47.100000" minlon="8.500000" maxlat="47.300000" maxlon="8.600000"""").containsMatchIn(xml),
            xml,
        )
    }

    @Test
    fun `drops characters XML can't hold, so the file reads back`() {
        val read = parser.parse(write().byteInputStream())
        assertEquals("Ride home", read.name)
        assertEquals("Summit", read.waypoints.single().name)
    }

    @Test
    fun `timed points round-trip with elevation, time and segments`() {
        val at = java.time.Instant.parse("2026-05-01T08:00:00Z")
        val full = Track(
            name = " ",
            points = dev.samuelq.gpx.core.model.TrackPoints.of(
                listOf(TrackPoint(1.0, 2.0, elevation = 10.04, time = at), TrackPoint(1.1, 2.1)),
                listOf(TrackPoint(1.2, 2.2, elevation = 12.0, time = at.plusSeconds(60))),
            ),
            waypoints = listOf(
                Waypoint(TrackPoint(1.0, 2.0, elevation = 5.0, time = at), name = null),
                Waypoint(TrackPoint(1.1, 2.1), name = "  "),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(full, out)
        val xml = out.toString(Charsets.UTF_8)
        val read = parser.parse(xml.byteInputStream())

        assertTrue(Regex("""<metadata>\s*<time>2026-05-01T08:00:00Z</time>""").containsMatchIn(xml), xml)
        assertTrue("<ele>10.0</ele>" in xml, xml)
        assertEquals(null, read.name)
        assertEquals(2, read.points.segmentCount)
        assertEquals(at.plusSeconds(60), read.points[2].time)
        assertEquals(null, read.points[1].elevation)
        assertEquals(listOf(null, null), read.waypoints.map { it.name })
        assertEquals(5.0, read.waypoints[0].point.elevation)
        assertEquals(at, read.waypoints[0].point.time)
    }

    @Test
    fun `an empty track writes no metadata but still reads back`() {
        val out = ByteArrayOutputStream()
        writer.write(Track(name = null, points = dev.samuelq.gpx.core.model.TrackPoints.EMPTY), out)
        val xml = out.toString(Charsets.UTF_8)

        assertFalse("<metadata" in xml, xml)
        assertTrue(parser.parse(xml.byteInputStream()).isEmpty)
    }

    @Test
    fun `only the characters XML forbids are dropped`() {
        assertEquals("a\tb\nc\rd", "a\tb\nc\rd".xmlSafe())
        assertEquals("ab", "a\u0000\u001F\uFFFE\uFFFFb".xmlSafe())
    }
}
