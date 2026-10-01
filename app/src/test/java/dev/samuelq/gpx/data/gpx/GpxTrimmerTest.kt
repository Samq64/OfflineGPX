package dev.samuelq.gpx.data.gpx

import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpxTrimmerTest {

    private val trimmer = GpxTrimmer({ KXmlParser() }, { KXmlSerializer() })
    private val parser = GpxParser { KXmlParser() }

    private val source = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="other" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:hr="urn:example:hr">
          <metadata><name>Kept metadata</name></metadata>
          <wpt lat="47.0" lon="8.0"><time>2026-05-01T08:00:00Z</time><desc>Start</desc></wpt>
          <wpt lat="99.0" lon="8.0"><desc>Unreadable</desc></wpt>
          <wpt lat="47.3" lon="8.3"><desc>End</desc></wpt>
          <trk>
            <name>Ride</name>
            <!-- a comment -->
            <trkseg>
              <trkpt lat="47.0" lon="8.0"><ele>400</ele><extensions><hr:bpm>100</hr:bpm></extensions></trkpt>
              <trkpt lat="47.1" lon="8.1"><ele>410</ele><extensions><hr:bpm>110</hr:bpm></extensions></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="91.0" lon="8.1"/>
              <trkpt lat="47.2" lon="8.2"><ele>420</ele></trkpt>
              <trkpt lat="47.3" lon="8.3"><ele>430</ele></trkpt>
            </trkseg>
            <extensions><hr:device>Watch</hr:device></extensions>
          </trk>
        </gpx>
    """.trimIndent()

    private fun trim(keepPoint: (Int) -> Boolean, keepWaypoint: (Int) -> Boolean = { true }): String {
        val out = ByteArrayOutputStream()
        trimmer.trim(source.byteInputStream(), out, keepPoint, keepWaypoint)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `kept points keep their extensions, and the rest of the file passes through`() {
        val trimmed = trim(keepPoint = { it >= 1 })
        val track = parser.parse(trimmed.byteInputStream())

        assertEquals(3, track.points.size)
        assertEquals(410f, track.points.elevation(0))
        assertEquals("Ride", track.name)
        assertTrue("110" in trimmed && "100" !in trimmed, trimmed)
        assertTrue("Kept metadata" in trimmed && "a comment" in trimmed && "Watch" in trimmed, trimmed)
    }

    @Test
    fun `indices count points as the parser does, skipping unreadable ones`() {
        // Index 2 is the second segment's first readable point; the unreadable one before it has none.
        val track = parser.parse(trim(keepPoint = { it == 2 }).byteInputStream())
        assertEquals(1, track.points.size)
        assertEquals(47.2, track.points.latitude(0))
    }

    @Test
    fun `a segment left empty is dropped, not kept as an empty element`() {
        val trimmed = trim(keepPoint = { it >= 2 })
        assertEquals(1, Regex("<trkseg").findAll(trimmed).count(), trimmed)
        assertEquals(1, parser.parse(trimmed.byteInputStream()).points.segmentCount)
    }

    @Test
    fun `waypoints are kept by index, unreadable ones having none`() {
        val trimmed = trim(keepPoint = { true }, keepWaypoint = { it == 1 })
        val waypoints = parser.parse(trimmed.byteInputStream()).waypoints
        assertEquals(listOf("End"), waypoints.map { it.description })
        assertFalse("Unreadable" in trimmed)
    }

    @Test
    fun `no blank lines are left where points were`() {
        val trimmed = trim(keepPoint = { it == 0 || it == 3 })
        assertFalse(Regex("\\n\\s*\\n").containsMatchIn(trimmed), trimmed)
    }
}
