package dev.samuelq.gpx.data.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PmtilesMetadataTest {

    @Test
    fun `reads a plain attribution string`() {
        val json = """{"name":"extract","attribution":"© Example Data Provider"}"""
        assertEquals("© Example Data Provider", PmtilesMetadata.parseAttribution(json))
    }

    @Test
    fun `strips the html anchor tippecanoe writes`() {
        val json = """
            {"attribution":"<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"}
        """.trimIndent()
        assertEquals("© OpenStreetMap contributors", PmtilesMetadata.parseAttribution(json))
    }

    @Test
    fun `decodes the html entity Leaflet's own OSM attribution defaults to`() {
        val json = """{"attribution":"&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"}"""
        assertEquals("© OpenStreetMap contributors", PmtilesMetadata.parseAttribution(json))
    }

    @Test
    fun `decodes a numeric copyright entity`() {
        assertEquals(
            "© 2024 Example",
            PmtilesMetadata.parseAttribution("""{"attribution":"&#169; 2024 Example"}"""),
        )
        assertEquals(
            "© 2024 Example",
            PmtilesMetadata.parseAttribution("""{"attribution":"&#xA9; 2024 Example"}"""),
        )
    }

    @Test
    fun `is null when the archive does not declare one`() {
        assertNull(PmtilesMetadata.parseAttribution("""{"name":"extract"}"""))
    }

    @Test
    fun `is null for a blank attribution rather than an empty line`() {
        assertNull(PmtilesMetadata.parseAttribution("""{"attribution":"   "}"""))
    }

    @Test
    fun `is null for malformed json instead of throwing`() {
        assertNull(PmtilesMetadata.parseAttribution("not json at all"))
    }

    @Test
    fun `is null when attribution is not a string`() {
        assertNull(PmtilesMetadata.parseAttribution("""{"attribution":{"nested":true}}"""))
    }
}
