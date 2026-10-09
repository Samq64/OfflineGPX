package dev.samuelq.gpx.data.gpx

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.kxml2.io.KXmlParser

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
              <trk>
                <name>Morning ride</name>
                <trkseg>
                  <trkpt lat="47.1" lon="8.5"><ele>430.2</ele><time>2026-05-01T08:00:00Z</time></trkpt>
                  <trkpt lat="47.2" lon="8.6"><ele>451.0</ele><time>2026-05-01T08:00:10Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent(),
        )

        assertEquals("Morning ride", track.name)
        assertEquals(2, track.points.size)
        assertEquals(47.1, track.points[0].latitude, 1e-9)
        assertEquals(430.2, track.points[0].elevation!!, 1e-3) // Stored as a Float.
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
            """.trimIndent(),
        )

        assertEquals(2, track.points.segmentCount)
        assertEquals(3, track.points.size)
        assertTrue(track.points.segmentStarts().contentEquals(intArrayOf(0, 2)))
    }

    @Test
    fun `reads GPX 1_0 namespace`() {
        val track = parse(
            """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"><trk><trkseg>
              <trkpt lat="47.0" lon="8.0"><ele>400</ele></trkpt>
            </trkseg></trk></gpx>
            """.trimIndent(),
        )

        assertEquals(1, track.points.size)
        assertEquals(400.0, track.points[0].elevation!!, 1e-9)
    }

    @Test
    fun `a route alone is no track`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><rte>
              <name>Planned</name>
              <rtept lat="47.0" lon="8.0"><ele>400</ele></rtept>
            </rte></gpx>
            """.trimIndent(),
        )

        assertNull(track.name)
        assertTrue(track.isEmpty)
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
            """.trimIndent(),
        )

        assertEquals("Started at the trailhead, rained the whole way back.", track.description)
    }

    @Test
    fun `reads the first track's type`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><type> hiking </type><trkseg><trkpt lat="47.0" lon="8.0"/></trkseg></trk>
              <trk><type>running</type><trkseg><trkpt lat="47.1" lon="8.0"/></trkseg></trk>
            </gpx>
            """.trimIndent(),
        )

        assertEquals("hiking", track.type)
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
            """.trimIndent(),
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
            """.trimIndent(),
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

    @Test
    fun `labels a waypoint by its name, else its desc or cmt, trimmed`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="1" lon="1"><name>
                Summit
              </name><cmt>Comment</cmt><desc>Description</desc></wpt>
              <wpt lat="2" lon="2"><cmt>Comment</cmt><desc>Description</desc></wpt>
              <wpt lat="3" lon="3"><cmt>Comment</cmt></wpt>
              <wpt lat="4" lon="4"><name> </name></wpt>
            </gpx>
            """.trimIndent(),
        )

        assertEquals(listOf("Summit", "Description", "Comment", null), track.waypoints.map { it.name })
    }

    @Test
    fun `reads a waypoint's elevation and time, skipping what it doesn't use`() {
        val waypoint = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="1" lon="2"><ele>12.5</ele><time>2026-05-01T08:00:00Z</time><sym>Flag</sym></wpt>
              <wpt lat="1"><name>No longitude</name></wpt>
              <wpt lat="95" lon="2"><name>Off the globe</name></wpt>
            </gpx>
            """.trimIndent(),
        ).waypoints.single()

        assertEquals(12.5, waypoint.point.elevation)
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), waypoint.point.time)
        assertNull(waypoint.name)
    }

    @Test
    fun `the first track's name and description win, and the metadata's name is ignored`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Metadata</name><author>Someone</author></metadata>
              <trk><name>First</name><desc>First desc</desc><type>ride</type><trkseg><trkpt lat="1" lon="1"/></trkseg></trk>
              <trk><name>Second</name><desc>Second desc</desc><trkseg><trkpt lat="2" lon="2"/></trkseg></trk>
              <rte><name>Route</name><rtept lat="3" lon="3"/></rte>
              <extensions><x/></extensions>
            </gpx>
            """.trimIndent(),
        )

        assertEquals("First", track.name)
        assertEquals("First desc", track.description)
        assertEquals(2, track.points.segmentCount)
    }

    @Test
    fun `a route beside a track is left out, name and all`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte><name>Plan</name><rtept lat="1" lon="1"/><rtept lat="1.1" lon="1.1"/></rte>
              <trk><trkseg>
                <trkpt lat="2" lon="2"><time>2024-05-01T10:00:00Z</time></trkpt>
                <trkpt lat="2.1" lon="2.1"><time>2024-05-01T10:01:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent(),
        )

        assertNull(track.name)
        assertEquals(listOf(2.0, 2.1), track.points.indices.map(track.points::latitude))
        assertTrue(track.points.indices.all(track.points::hasTime))
    }

    @Test
    fun `skips unreadable points`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Metadata</name></metadata>
              <trk><trkseg><trkpt lat="x" lon="3"/><trkpt lat="4" lon="4"><extensions/></trkpt></trkseg></trk>
            </gpx>
            """.trimIndent(),
        )

        assertNull(track.name)
        assertEquals(1, track.points.size)
    }

    @Test
    fun `an empty or unclosed element is read as absent`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name/><trkseg>
              <trkpt lat="1" lon="1"><ele></ele><time/></trkpt>
              <trkpt lat="2" lon="2"><ele>nope</ele></trkpt>
            """.trimIndent(),
        )

        assertNull(track.name)
        assertEquals(2, track.points.size)
        assertNull(track.points[0].elevation)
        assertNull(track.points[0].time)
        assertNull(track.points[1].elevation)
    }

    @Test
    fun `malformed XML is a parse error, not a crash`() {
        val error = assertFailsWith<GpxParseException> {
            parse("""<gpx><trk><trkseg><trkpt lat="1" lon="1"></trkseg></trk></gpx>""")
        }
        assertTrue(error.message!!.startsWith("Malformed XML"))
        assertFailsWith<GpxParseException> { parse("not xml at all") }
    }

    @Test
    fun `a parser that can't start is a parse error`() {
        val failing = GpxParser {
            object : KXmlParser() {
                override fun setInput(input: java.io.InputStream?, encoding: String?) =
                    throw org.xmlpull.v1.XmlPullParserException("no input")
            }
        }
        assertFailsWith<GpxParseException> { failing.parse("<gpx/>".byteInputStream()) }
    }

    @Test
    fun `a lowercase z is still UTC`() {
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), GpxParser.parseGpxTime("2026-05-01T08:00:00z"))
    }

    @Test
    fun `more points than the cap, in any segment, is too large`() {
        // The fixture adds one point in a segment of its own.
        val cap = GpxParser.MAX_POINTS
        assertEquals(cap, parser.parse(oversizedGpx(cap - 1)).points.size)
        assertFailsWith<GpxTooLargeException> { parser.parse(oversizedGpx(cap)) }
    }

    @Test
    fun `unknown children of a segment, and routes, are skipped`() {
        val track = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte><desc>Route</desc><rtept lat="1" lon="1"/></rte>
              <trk><trkseg><trkpt lat="2" lon="2"/><trkpt lat="3"/><extensions><x/></extensions></trkseg></trk>
            </gpx>
            """.trimIndent(),
        )

        assertEquals(1, track.points.size)
        assertEquals(1, track.points.segmentCount)
    }

    @Test
    fun `a file cut off inside an unknown element keeps what came before`() {
        val track = parse("""<gpx><trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk><extensions><a><b>""")
        assertEquals(1, track.points.size)
    }

    private fun colored(extensions: String) = parse(
        """
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:gpx_style="http://www.topografix.com/GPX/gpx_style/0/2"
             xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3"
             xmlns:osmand="https://osmand.net">
          <trk><extensions>$extensions</extensions><trkseg><trkpt lat="1" lon="1"/></trkseg></trk>
        </gpx>
        """.trimIndent(),
    ).lineColor

    @Test
    fun `reads a track's colour from gpx_style, OsmAnd or Garmin, preferring the exact ones`() {
        val garmin = "<gpxx:TrackExtension><gpxx:DisplayColor>DarkCyan</gpxx:DisplayColor></gpxx:TrackExtension>"
        val osmAnd = "<osmand:color>#80ff8800</osmand:color>"
        val style = "<gpx_style:line><gpx_style:color>2a95b9</gpx_style:color></gpx_style:line>"
        assertEquals(0x008080, colored(garmin))
        assertEquals(0xFF8800, colored(garmin + osmAnd))
        assertEquals(0x2A95B9, colored(garmin + osmAnd + style))
        assertNull(
            colored("<gpxx:TrackExtension><gpxx:DisplayColor>Transparent</gpxx:DisplayColor></gpxx:TrackExtension>"),
        )
        assertNull(colored("<gpx_style:line><gpx_style:color>red</gpx_style:color></gpx_style:line>"))
    }

    @Test
    fun `a colour is found among other extensions, and the first track's wins`() {
        val line = "<gpx_style:line><gpx_style:width>3</gpx_style:width>" +
            "<gpx_style:color>2a95b9</gpx_style:color></gpx_style:line>"
        val garmin = "<gpxx:TrackExtension><gpxx:Extensions/>" +
            "<gpxx:DisplayColor>Red</gpxx:DisplayColor></gpxx:TrackExtension>"
        val others = "<other xmlns=\"urn:x\"><color>000000</color></other><osmand:width>3</osmand:width>" +
            "<gpx_style:text/><gpxx:Other/>"
        assertEquals(0x2A95B9, colored(others + line))
        assertEquals(0xFF0000, colored(garmin))

        val laps = parse(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
              <trk><number>1</number><extensions>$garmin</extensions><trkseg><trkpt lat="1" lon="1"/></trkseg></trk>
              <trk><extensions><gpxx:TrackExtension><gpxx:DisplayColor>Blue</gpxx:DisplayColor></gpxx:TrackExtension></extensions></trk>
            </gpx>
            """.trimIndent(),
        )
        assertEquals(0xFF0000, laps.lineColor)
    }
}

/** A `<trkseg>` of [count] points, then one more segment of one point at 50, 50; streamed. */
internal fun oversizedGpx(count: Int): java.io.InputStream {
    val head = """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>""".toByteArray()
    val point = """<trkpt lat="1" lon="1"/>""".toByteArray()
    val tail = """</trkseg><trkseg><trkpt lat="50" lon="50"/></trkseg></trk></gpx>""".toByteArray()
    val total = head.size.toLong() + point.size.toLong() * count + tail.size
    return object : java.io.InputStream() {
        var at = 0L
        override fun read(): Int {
            if (at >= total) return -1
            val i = at++
            return when {
                i < head.size -> head[i.toInt()]
                i < total - tail.size -> point[((i - head.size) % point.size).toInt()]
                else -> tail[(i - (total - tail.size)).toInt()]
            }.toInt() and 0xff
        }
    }.buffered(1 shl 16)
}
