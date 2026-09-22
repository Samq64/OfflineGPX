package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import org.mapsforge.map.awt.graphics.AwtGraphicFactory
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import org.mapsforge.map.rendertheme.rule.RenderThemeHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

/**
 * The generated render theme, parsed by the same parser the renderer uses.
 *
 * This is the check worth having: mapsforge answers a malformed theme with a map that
 * draws nothing at all rather than with an error anyone would notice, and the theme is
 * built from strings at runtime, so nothing else would catch a typo in a rule.
 *
 * Parsed here through the desktop graphics factory. The parser is shared; only the paint
 * objects it builds differ from Android's.
 */
class MapRenderThemeTest {

    private fun parse(xml: String) = RenderThemeHandler.getRenderTheme(
        AwtGraphicFactory.INSTANCE,
        DisplayModel(),
        object : XmlRenderTheme {
            override fun getMenuCallback(): XmlRenderThemeMenuCallback? = null
            override fun setMenuCallback(callback: XmlRenderThemeMenuCallback?) = Unit
            override fun getRelativePathPrefix(): String = ""
            override fun getRenderThemeAsStream(): InputStream = ByteArrayInputStream(xml.toByteArray())
            override fun getResourceProvider(): XmlThemeResourceProvider? = null
            override fun setResourceProvider(provider: XmlThemeResourceProvider?) = Unit
        },
    )

    private val lightXml = MapRenderTheme.xml(
        land = Color(0xFFF4F1EC),
        label = Color(0xFF1B1B1F),
        background = Color(0xFFE8E4DE),
    )

    private val darkXml = MapRenderTheme.xml(
        land = Color(0xFF1A1C1E),
        label = Color(0xFFE3E2E6),
        background = Color(0xFF111316),
    )

    @Test
    fun `the light theme is a theme mapsforge accepts`() {
        assertNotNull(parse(lightXml))
    }

    /**
     * Built separately rather than by inverting the light one - every derived colour moves
     * away from the background, so the dark branch takes a different path through the
     * generator and can break on its own.
     */
    @Test
    fun `the dark theme is a theme mapsforge accepts`() {
        assertNotNull(parse(darkXml))
    }

    /**
     * The tags the app actually depends on being drawn. A rule silently dropped because
     * its key was misspelled parses perfectly well and renders nothing, so the presence of
     * each is asserted rather than inferred from the parse succeeding.
     */
    @Test
    fun `the vocabulary the app relies on is present`() {
        listOf(
            "natural", "waterway", "landuse", "leisure", "building",
            "highway", "railway", "place",
        ).forEach { assertContains(lightXml, """k="$it"""") }
    }

    /** Subway runs underground; drawing it draws a line over ground it never crosses. */
    @Test
    fun `surface rail is drawn and subway is not`() {
        assertContains(lightXml, "rail|light_rail|narrow_gauge")
        assert(!lightXml.contains("subway")) { "subway should not be drawn" }
    }

    /**
     * The transparent background is load-bearing: the map screen paints each file's own box
     * underneath, so that ground no file covers reads as empty rather than as land.
     */
    @Test
    fun `the background is transparent`() {
        assertContains(lightXml, """map-background="#00000000"""")
    }
}
