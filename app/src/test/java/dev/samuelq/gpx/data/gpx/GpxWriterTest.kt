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
}
