package dev.samuelq.gpx.data.gpx

import org.kxml2.io.KXmlParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxParserTest {

    // android.jar's XmlPullParser is a stub in unit tests, hence kxml2.
    private val parser = GpxParser { KXmlParser() }

    private fun parse(xml: String) = parser.parse(xml.byteInputStream())

    @Test
    fun `reads points with elevation and time`() {
        val track = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Morning ride</name></metadata>
              <trk>
                <trkseg>
                  <trkpt lat="47.1" lon="8.5"><ele>430.2</ele><time>2026-05-01T08:00:00Z</time></trkpt>
                  <trkpt lat="47.2" lon="8.6"><ele>451.0</ele><time>2026-05-01T08:00:10Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals("Morning ride", track.name)
        assertEquals(2, track.points.size)
        assertEquals(47.1, track.points[0].latitude, 1e-9)
        assertEquals(430.2, track.points[0].elevation!!, 1e-9)
        assertEquals(Instant.parse("2026-05-01T08:00:10Z"), track.points[1].time)
    }

    @Test
    fun `keeps segments separate`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk>
              <trkseg><trkpt lat="1" lon="1"/><trkpt lat="1.001" lon="1"/></trkseg>
              <trkseg><trkpt lat="2" lon="2"/></trkseg>
            </trk></gpx>
            """.trimIndent()
        )

        assertEquals(2, track.segments.size)
        assertEquals(3, track.points.size)
        assertTrue(track.segmentStartIndices.contentEquals(intArrayOf(0, 2)))
    }

    @Test
    fun `reads GPX 1_0 namespace`() {
        val track = parse(
            """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"><trk><trkseg>
              <trkpt lat="47.0" lon="8.0"><ele>400</ele></trkpt>
            </trkseg></trk></gpx>
            """.trimIndent()
        )

        assertEquals(1, track.points.size)
        assertEquals(400.0, track.points[0].elevation!!, 1e-9)
    }

    @Test
    fun `falls back to a route when there is no track`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><rte>
              <name>Planned</name>
              <rtept lat="47.0" lon="8.0"><ele>400</ele></rtept>
              <rtept lat="47.1" lon="8.1"><ele>420</ele></rtept>
            </rte></gpx>
            """.trimIndent()
        )

        assertEquals("Planned", track.name)
        assertEquals(2, track.points.size)
        assertEquals(1, track.segments.size)
        assertNull(track.points[0].time)
    }

    @Test
    fun `reads a track's description`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk>
              <name>Loop</name>
              <desc>Started at the trailhead, rained the whole way back.</desc>
              <trkseg><trkpt lat="47.0" lon="8.0"/></trkseg>
            </trk></gpx>
            """.trimIndent()
        )

        assertEquals("Started at the trailhead, rained the whole way back.", track.description)
    }

    @Test
    fun `skips vendor extensions and unknown elements`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="9" lon="9"><name>Not a track point</name></wpt>
              <trk><trkseg>
                <trkpt lat="47.0" lon="8.0">
                  <ele>400</ele>
                  <extensions><gpxtpx:TrackPointExtension xmlns:gpxtpx="x"><gpxtpx:hr>142</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(1, track.points.size)
        assertEquals(400.0, track.points[0].elevation!!, 1e-9)
    }

    @Test
    fun `drops points with unusable coordinates instead of failing the file`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
              <trkpt lat="47.0" lon="8.0"/>
              <trkpt lon="8.1"/>
              <trkpt lat="not-a-number" lon="8.2"/>
              <trkpt lat="200" lon="8.3"/>
              <trkpt lat="47.4" lon="8.4"/>
            </trkseg></trk></gpx>
            """.trimIndent()
        )

        assertEquals(2, track.points.size)
    }

    @Test
    fun `rejects a document that is not gpx`() {
        assertFailsWith<GpxParseException> {
            parse("<kml><Placemark/></kml>")
        }
    }

    @Test
    fun `accepts the timestamp spellings exporters actually emit`() {
        val expected = Instant.parse("2026-05-01T08:00:00Z")
        assertEquals(expected, GpxParser.parseGpxTime("2026-05-01T08:00:00Z"))
        assertEquals(expected, GpxParser.parseGpxTime("2026-05-01T10:00:00+02:00"))
        assertEquals(expected, GpxParser.parseGpxTime("2026-05-01T08:00:00"))
        assertEquals(expected, GpxParser.parseGpxTime("  2026-05-01T08:00:00.000Z  "))
        assertNull(GpxParser.parseGpxTime("last tuesday"))
        assertNull(GpxParser.parseGpxTime(""))
    }

    /** A zone sign only ever follows the `T`, so the date's own hyphens mustn't read as one. */
    @Test
    fun `picks a parser from the spelling rather than by trial and error`() {
        val expected = Instant.parse("2026-05-01T08:00:00Z")
        assertEquals(expected, GpxParser.parseGpxTime("2026-05-01T05:00:00-03:00"))
        assertEquals(expected, GpxParser.parseGpxTime("2026-05-01T10:30:00.000+02:30"))
        assertEquals(expected, GpxParser.parseGpxTime("2026-05-01T08:00:00.000"))
        // A date that is only a date has no `T` to look past, and is not a timestamp.
        assertNull(GpxParser.parseGpxTime("2026-05-01"))
        assertNull(GpxParser.parseGpxTime("2026-05-01T08:00:00+"))
    }
}
