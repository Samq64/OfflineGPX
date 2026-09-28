package dev.samuelq.gpx.data.gpx

import org.kxml2.io.KXmlParser
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxNameRewriterTest {

    private val directory: File = Files.createTempDirectory("rewriter").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun rewrite(xml: String, name: String?): String? {
        val source = File(directory, "source.gpx").apply { writeText(xml) }
        val destination = File(directory, "destination.gpx")
        if (!GpxNameRewriter.rewrite(source, destination, name)) return null
        return destination.readText()
    }

    private fun nameOf(xml: String): String? =
        GpxParser { KXmlParser() }.parse(xml.byteInputStream()).name

    private fun gpx(trackHeader: String) =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Garmin" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Not the track name</name></metadata>
          <wpt lat="47.0" lon="8.0"><name>A waypoint</name></wpt>
          <trk>$trackHeader
            <trkseg>
              <trkpt lat="47.1" lon="8.5"><ele>430.2</ele>
                <extensions><gpxtpx:hr xmlns:gpxtpx="x">142</gpxtpx:hr></extensions>
              </trkpt>
              <trkpt lat="47.2" lon="8.6"><ele>451.0</ele></trkpt>
            </trkseg>
          </trk>
        </gpx>
        """.trimIndent()

    @Test
    fun `replaces an existing name`() {
        val out = requireNotNull(rewrite(gpx("<name>Old name</name>"), "New name"))

        assertEquals("New name", nameOf(out))
    }

    @Test
    fun `keeps everything the parser does not model`() {
        val out = requireNotNull(rewrite(gpx("<name>Old name</name>"), "New name"))

        // A round trip through GpxWriter would drop these.
        assertTrue(out.contains("gpxtpx:hr"), "heart rate extension survived")
        assertTrue(out.contains("""<wpt lat="47.0" lon="8.0">"""), "waypoint survived")
        assertTrue(out.contains("<metadata><name>Not the track name</name></metadata>"))
        assertTrue(out.contains("""creator="Garmin""""))
    }

    @Test
    fun `inserts a name when the track has none`() {
        val out = requireNotNull(rewrite(gpx(""), "Given a name"))

        assertEquals("Given a name", nameOf(out))
    }

    @Test
    fun `replaces a self-closing name`() {
        val out = requireNotNull(rewrite(gpx("<name/>"), "Filled in"))

        assertEquals("Filled in", nameOf(out))
    }

    @Test
    fun `clearing a name leaves the track unnamed`() {
        // No `<metadata><name>`, which the parser would fall back to.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Old name</name>
                <trkseg><trkpt lat="47.1" lon="8.5"/></trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val out = requireNotNull(rewrite(xml, null))

        assertNull(nameOf(out))
    }

    @Test
    fun `escapes a name that would otherwise break the document`() {
        val out = requireNotNull(rewrite(gpx("<name>Old</name>"), """Ben & Jerry <ride>"""))

        assertEquals("Ben & Jerry <ride>", nameOf(out))
    }

    @Test
    fun `keeps a name outside the latin range intact`() {
        val out = requireNotNull(rewrite(gpx("<name>Old</name>"), "Ku-ring-gai · 秋の道"))

        assertEquals("Ku-ring-gai · 秋の道", nameOf(out))
    }

    @Test
    fun `does not touch the metadata name`() {
        val out = requireNotNull(rewrite(gpx("<name>Old name</name>"), "New name"))

        assertTrue(out.contains("<metadata><name>Not the track name</name></metadata>"))
    }

    @Test
    fun `leaves a prefixed document in its own namespace`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <g:gpx version="1.1" xmlns:g="http://www.topografix.com/GPX/1/1">
              <g:trk><g:name>Old</g:name>
                <g:trkseg><g:trkpt lat="47.1" lon="8.5"/></g:trkseg>
              </g:trk>
            </g:gpx>
        """.trimIndent()

        val out = requireNotNull(rewrite(xml, "Renamed"))

        assertEquals("Renamed", nameOf(out))
    }

    @Test
    fun `inserts under the document's own prefix`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <g:gpx version="1.1" xmlns:g="http://www.topografix.com/GPX/1/1">
              <g:trk>
                <g:trkseg><g:trkpt lat="47.1" lon="8.5"/></g:trkseg>
              </g:trk>
            </g:gpx>
        """.trimIndent()

        val out = requireNotNull(rewrite(xml, "Renamed"))

        assertTrue(out.contains("<g:name>Renamed</g:name>"), "used the document's prefix")
        assertEquals("Renamed", nameOf(out))
    }

    @Test
    fun `refuses a file it cannot splice safely rather than mangling it`() {
        // A declared encoding that is not UTF-8: splicing UTF-8 bytes in would corrupt it.
        val utf16 = """
            <?xml version="1.0" encoding="UTF-16"?>
            <gpx version="1.1"><trk><name>Old</name><trkseg/></trk></gpx>
        """.trimIndent()
        assertNull(rewrite(utf16, "New"))

        assertNull(rewrite("""<?xml version="1.0"?><gpx><wpt lat="1" lon="2"/></gpx>""", "New"))
    }

    @Test
    fun `does not write the destination when it refuses`() {
        val source = File(directory, "refused.gpx").apply { writeText("not xml at all") }
        val destination = File(directory, "refused-out.gpx")

        assertFalse(GpxNameRewriter.rewrite(source, destination, "New"))
        assertFalse(destination.exists())
    }

    @Test
    fun `copies a body longer than the head it inspects`() {
        val points = buildString {
            repeat(5_000) { append("""<trkpt lat="47.1" lon="8.5"><ele>430.0</ele></trkpt>""") }
        }
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Old</name><trkseg>$points</trkseg></trk>
            </gpx>
        """.trimIndent()

        val out = requireNotNull(rewrite(xml, "Long ride"))

        assertEquals("Long ride", nameOf(out))
        assertEquals(5_000, out.split("<trkpt ").size - 1)
    }
}
