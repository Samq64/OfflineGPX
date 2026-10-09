package dev.samuelq.gpx.data.gpx

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer

class GpxTrimmerTest {

    private val trimmer = GpxTrimmer({ KXmlParser() }, { KXmlSerializer() })
    private val parser = GpxParser { KXmlParser() }

    private val source = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="other" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:hr="urn:example:hr">
          <metadata><name>Kept metadata</name><bounds minlat="47.0" minlon="8.0" maxlat="47.3" maxlon="8.3"/></metadata>
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
        assertEquals(listOf("End"), waypoints.map { it.name })
        assertFalse("Unreadable" in trimmed)
    }

    @Test
    fun `stale bounds are dropped`() {
        val trimmed = trim(keepPoint = { it >= 1 })
        assertFalse("<bounds" in trimmed, trimmed)
        assertTrue("Kept metadata" in trimmed, trimmed)
    }

    @Test
    fun `no blank lines are left where points were`() {
        val trimmed = trim(keepPoint = { it == 0 || it == 3 })
        assertFalse(Regex("\\n\\s*\\n").containsMatchIn(trimmed), trimmed)
    }

    @Test
    fun `keeping every point keeps the bounds`() {
        val out = ByteArrayOutputStream()
        trimmer.trim(source.byteInputStream(), out)
        assertTrue("<bounds" in out.toString(Charsets.UTF_8))
    }

    private fun rename(xml: String, name: String): String {
        val out = ByteArrayOutputStream()
        trimmer.trim(xml.byteInputStream(), out, name = name)
        return out.toString(Charsets.UTF_8)
    }

    private fun nameOf(xml: String): String? = parser.parse(xml.byteInputStream()).name

    private fun retype(xml: String, type: String, name: String? = null): String {
        val out = ByteArrayOutputStream()
        trimmer.trim(xml.byteInputStream(), out, name = name, type = type)
        return out.toString(Charsets.UTF_8)
    }

    private fun typeOf(xml: String): String? = parser.parse(xml.byteInputStream()).type

    private fun gpx(trackHeader: String) =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Not the track name</name></metadata>
          <trk>$trackHeader
            <trkseg><trkpt lat="47.1" lon="8.5"/></trkseg>
          </trk>
        </gpx>
        """.trimIndent()

    @Test
    fun `replaces a track's type`() {
        val out = retype(gpx("<name>Ride</name><type>running</type>"), "Commute")
        assertEquals("Commute", typeOf(out))
        assertFalse("running" in out, out)
        assertEquals("Ride", nameOf(out))
    }

    @Test
    fun `adds a type where the schema orders it, before extensions and segments`() {
        val out = retype(source, "Commute")
        assertEquals("Commute", typeOf(out))
        assertTrue(out.indexOf("<type>") in out.indexOf("a comment")..out.indexOf("<trkseg>"), out)

        val beforeExtensions = retype(gpx("<name>Ride</name><extensions/>"), "Commute")
        assertTrue(beforeExtensions.indexOf("<type>") < beforeExtensions.indexOf("<extensions"), beforeExtensions)
        assertNull(Regex("\\n\\s*\\n").find(retype(gpx(""), "Commute")))
    }

    @Test
    fun `a name and type go in together, in order`() {
        val out = retype(gpx(""), "Commute", name = "Ride")
        assertEquals("Ride", nameOf(out))
        assertEquals("Commute", typeOf(out))
        assertTrue(out.indexOf("<name>") < out.indexOf("<type>"), out)
    }

    @Test
    fun `a blank type removes the track's, and a track with no children still gets one`() {
        assertNull(typeOf(retype(gpx("<type>running</type>"), " ")))
        assertEquals("Commute", typeOf(retype("<gpx><trk></trk></gpx>", "Commute")))
    }

    @Test
    fun `types only the first track`() {
        val out = retype(source.replace("</trk>", "</trk><trk><type>Lap</type><trkseg/></trk>"), "Commute")
        assertEquals("Commute", typeOf(out))
        assertTrue("<type>Lap</type>" in out, out)
    }

    @Test
    fun `replaces a track's name, leaving the metadata's`() {
        val out = rename(gpx("<name>Old name</name>"), "New name")
        assertEquals("New name", nameOf(out))
        assertTrue("<metadata><name>Not the track name</name></metadata>" in out, out)
        assertFalse("Old name" in out, out)
    }

    @Test
    fun `adds a name to a track with none, or an empty one`() {
        assertEquals("Given a name", nameOf(rename(gpx(""), "Given a name")))
        assertEquals("Filled in", nameOf(rename(gpx("<name/>"), "Filled in")))
    }

    @Test
    fun `a blank name removes the track's`() {
        val out = rename(source, " ")
        assertFalse("Ride" in out, out)
        assertNull(nameOf(out))
        // Only the track's: the metadata's is left as it was.
        assertTrue("<name>Kept metadata</name>" in out, out)
    }

    @Test
    fun `names only the first track`() {
        val out = rename(source.replace("</trk>", "</trk><trk><name>Lap</name><trkseg/></trk>"), "Renamed")
        assertEquals("Renamed", nameOf(out))
        assertTrue("<name>Lap</name>" in out, out)
    }

    @Test
    fun `a name is escaped and kept whole`() {
        assertEquals("Ben & Jerry <ride>", nameOf(rename(gpx("<name>Old</name>"), "Ben & Jerry <ride>")))
        assertEquals("Ku-ring-gai · 秋の道", nameOf(rename(gpx("<name>Old</name>"), "Ku-ring-gai · 秋の道")))
    }

    @Test
    fun `a prefixed document keeps its prefix`() {
        val prefixed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <g:gpx version="1.1" xmlns:g="http://www.topografix.com/GPX/1/1">
              <g:trk>%s
                <g:trkseg><g:trkpt lat="47.1" lon="8.5"/></g:trkseg>
              </g:trk>
            </g:gpx>
        """.trimIndent()

        val replaced = rename(prefixed.format("<g:name>Old</g:name>"), "Renamed")
        assertEquals("Renamed", nameOf(replaced))
        val added = rename(prefixed.format(""), "Renamed")
        assertTrue("<g:name>Renamed</g:name>" in added, added)
    }

    @Test
    fun `renaming keeps every point and waypoint`() {
        val renamed = parser.parse(rename(source, "Renamed").byteInputStream())
        val original = parser.parse(source.byteInputStream())
        assertEquals(original.points.size, renamed.points.size)
        assertEquals(original.waypoints, renamed.waypoints)
        assertNull(Regex("\\n\\s*\\n").find(rename(gpx(""), "Named")))
    }

    @Test
    fun `a track with no children still gets its name`() {
        assertEquals("Named", nameOf(rename(gpx("").replace(Regex("<trk>[\\s\\S]*</trk>"), "<trk></trk>"), "Named")))
        // A blank name for a childless track writes nothing.
        assertFalse("<name>" in rename("<gpx><trk></trk></gpx>", ""))
    }

    @Test
    fun `unreadable and stray points get no index, as in the parser`() {
        val doc = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk>
              <extensions><trkpt lat="8" lon="8"/></extensions>
              <trkseg>
                <trkpt lon="1"/><trkpt lat="91" lon="1"/><trkpt lat="2" lon="2"/><trkpt lat="3" lon="3"/>
              </trkseg>
            </trk></gpx>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        trimmer.trim(doc.byteInputStream(), out, keepPoint = { it == 1 })

        val written = out.toString(Charsets.UTF_8)
        assertTrue("lat=\"8\"" in written, written)
        val track = parser.parse(written.byteInputStream())
        assertEquals(listOf(3.0), track.points.indices.map(track.points::latitude))
    }

    @Test
    fun `route points are kept whole and not counted`() {
        val doc = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte><rtept lat="9" lon="9"/></rte>
              <trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk>
            </gpx>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        trimmer.trim(doc.byteInputStream(), out, keepPoint = { it == 1 })

        val written = out.toString(Charsets.UTF_8)
        assertTrue("lat=\"9\"" in written)
        val track = parser.parse(written.byteInputStream())
        assertEquals(listOf(2.0), track.points.indices.map(track.points::latitude))
    }

    @Test
    fun `CDATA, entities, comments and processing instructions pass through`() {
        val doc = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?xml-stylesheet href="style.xsl"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><desc><![CDATA[<b>raw</b>]]> &amp; more</desc></metadata>
              <?vendor data?>
              <trk><trkseg><trkpt lat="1" lon="1"/><!-- gone --><?gone too?><![CDATA[gone]]><trkpt lat="2" lon="2"/></trkseg></trk>
            </gpx>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        trimmer.trim(doc.byteInputStream(), out, keepPoint = { it == 1 })
        val trimmed = out.toString(Charsets.UTF_8)

        assertTrue("<![CDATA[<b>raw</b>]]>" in trimmed, trimmed)
        assertTrue("&amp; more" in trimmed, trimmed)
        assertTrue("<?vendor data?>" in trimmed, trimmed)
        assertFalse("gone" in trimmed, trimmed)
        assertEquals(1, parser.parse(trimmed.byteInputStream()).points.size)
    }

    @Test
    fun `a doctype is dropped rather than copied`() {
        val doc = """<?xml version="1.0"?><!DOCTYPE gpx>""" +
            """<gpx version="1.1"><trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk></gpx>"""
        val out = ByteArrayOutputStream()
        trimmer.trim(doc.byteInputStream(), out)
        val trimmed = out.toString(Charsets.UTF_8)

        assertFalse("DOCTYPE" in trimmed, trimmed)
        assertEquals(1, parser.parse(trimmed.byteInputStream()).points.size)
    }

    @Test
    fun `waypoints alone can be cut, keeping every point and the bounds`() {
        val out = ByteArrayOutputStream()
        trimmer.trim(source.byteInputStream(), out, keepWaypoint = { it == 0 })
        val trimmed = out.toString(Charsets.UTF_8)
        val track = parser.parse(trimmed.byteInputStream())

        assertEquals(listOf("Start"), track.waypoints.map { it.name })
        assertEquals(parser.parse(source.byteInputStream()).points.size, track.points.size)
        assertTrue("<bounds" in trimmed, trimmed)
    }

    @Test
    fun `namespaced attributes such as the schema location survive`() {
        val doc = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://www.topografix.com/GPX/1/1 gpx.xsd">
              <trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2"/></trkseg></trk>
            </gpx>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        trimmer.trim(doc.byteInputStream(), out, keepPoint = { true })
        val trimmed = out.toString(Charsets.UTF_8)

        assertTrue(
            Regex("""\w+:schemaLocation="http://www.topografix.com/GPX/1/1 gpx.xsd"""").containsMatchIn(trimmed),
            trimmed,
        )
        assertEquals(1, parser.parse(trimmed.byteInputStream()).points.size)
    }

    @Test
    fun `a blank name adds nothing to a track without one`() {
        val out = rename(gpx(""), " ")
        assertNull(nameOf(out))
        assertEquals(
            listOf("Not the track name"),
            Regex("<name>([^<]*)</name>").findAll(out).map {
                it.groupValues[1]
            }.toList(),
            out,
        )
    }

    @Test
    fun `a cut segment takes its other children with it`() {
        val doc = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk>
              <trkseg><extensions><x>1</x></extensions><trkpt lat="1" lon="1"/></trkseg>
              <trkseg><trkpt lat="2" lon="2"/></trkseg>
            </trk></gpx>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        trimmer.trim(doc.byteInputStream(), out, keepPoint = { it == 1 })
        val trimmed = out.toString(Charsets.UTF_8)

        assertFalse("<x>" in trimmed, trimmed)
        assertEquals(1, Regex("<trkseg").findAll(trimmed).count(), trimmed)
    }

    private fun recolour(xml: String, color: String): String {
        val out = ByteArrayOutputStream()
        trimmer.trim(xml.byteInputStream(), out, color = color)
        return out.toString(Charsets.UTF_8)
    }

    private fun colorOf(xml: String): String? = parser.parse(xml.byteInputStream()).displayColor

    @Test
    fun `a colour goes in its own extensions before the segments, after the type`() {
        val out = recolour(gpx("<name>Ride</name><type>running</type>"), "Cyan")
        assertEquals("Cyan", colorOf(out))
        assertTrue(
            out.indexOf(
                "</type>",
            ) < out.indexOf("<extensions") && out.indexOf("</extensions>") < out.indexOf("<trkseg"),
            out,
        )
        assertFalse("gpx_style" in out, out)
        assertEquals("Cyan", colorOf(recolour("<gpx><trk></trk></gpx>", "Cyan")))
    }

    @Test
    fun `a colour joins the track's extensions, replacing other apps' colours and keeping the rest`() {
        val styled = source.replace(
            "<extensions><hr:device>",
            """<extensions xmlns:gpxx="${GpxColors.GARMIN_NAMESPACE}" xmlns:osmand="https://osmand.net" """ +
                """xmlns:gpx_style="http://www.topografix.com/GPX/gpx_style/0/2">""" +
                "<gpxx:TrackExtension><gpxx:DisplayColor>Red</gpxx:DisplayColor></gpxx:TrackExtension>" +
                "<osmand:color>#ffff0000</osmand:color>" +
                "<gpx_style:line><gpx_style:color>ff0000</gpx_style:color></gpx_style:line><hr:device>",
        )
        val out = recolour(styled, "Green")
        assertEquals("Green", colorOf(out))
        // A new one before the segments, the stripped one after them, and two on points.
        assertEquals(4, Regex("<extensions").findAll(out).count(), out)
        assertFalse(">Red<" in out || "ff0000" in out, out)
        assertTrue("<hr:device>Watch</hr:device>" in out, out)
    }

    @Test
    fun `colours only the first track`() {
        val second = "<trk><extensions><gpxx:TrackExtension xmlns:gpxx=\"${GpxColors.GARMIN_NAMESPACE}\">" +
            "<gpxx:DisplayColor>Red</gpxx:DisplayColor></gpxx:TrackExtension></extensions><trkseg/></trk>"
        val out = recolour(gpx("").replace("</trk>", "</trk>$second"), "Blue")
        assertEquals("Blue", colorOf(out))
        assertTrue(">Red<" in out, out)
    }
}
