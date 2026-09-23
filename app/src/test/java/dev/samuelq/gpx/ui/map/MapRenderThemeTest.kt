package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import org.oscim.backend.CanvasAdapter
import org.oscim.backend.canvas.Bitmap
import org.oscim.backend.canvas.Canvas
import org.oscim.backend.canvas.Paint
import org.oscim.theme.ThemeFile
import org.oscim.theme.ThemeLoader
import org.oscim.theme.XmlRenderThemeMenuCallback
import org.oscim.theme.XmlThemeResourceProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull

/**
 * The generated render theme, parsed by the same parser the renderer uses.
 *
 * This is the check worth having: a malformed theme answers with a map that draws nothing
 * at all rather than with an error anyone would notice, and the theme is built from
 * strings at runtime, so nothing else would catch a typo in a rule.
 *
 * Parsed here with a do-nothing graphics backend. The parser only asks it for paints and
 * the dash textures, whose contents don't matter to whether the theme parses.
 */
class MapRenderThemeTest {

    private fun parse(xml: String) = run {
        NullGraphics.install()
        var mapsforge = false
        ThemeLoader.load(
            object : ThemeFile {
                override fun getMenuCallback(): XmlRenderThemeMenuCallback? = null
                override fun setMenuCallback(callback: XmlRenderThemeMenuCallback?) = Unit
                override fun getRelativePathPrefix(): String = ""
                override fun getRenderThemeAsStream(): InputStream = ByteArrayInputStream(xml.toByteArray())
                override fun getResourceProvider(): XmlThemeResourceProvider? = null
                override fun setResourceProvider(provider: XmlThemeResourceProvider?) = Unit
                override fun isMapsforgeTheme() = mapsforge
                override fun setMapsforgeTheme(value: Boolean) { mapsforge = value }
            },
        )
    }

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
    fun `the light theme is a theme VTM accepts`() {
        assertNotNull(parse(lightXml))
    }

    /**
     * Built separately rather than by inverting the light one - every derived colour moves
     * away from the background, so the dark branch takes a different path through the
     * generator and can break on its own.
     */
    @Test
    fun `the dark theme is a theme VTM accepts`() {
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

    /** What VTM clears the screen to, so ground beyond every file matches the app's own. */
    @Test
    fun `outside the maps is the background colour`() {
        assertContains(darkXml, """map-background-outside="#111316"""")
    }
}

/** A graphics backend whose every object is a proxy answering zero, false or null. */
private object NullGraphics : CanvasAdapter() {
    private var installed = false

    fun install() {
        if (!installed) init(this)
        installed = true
    }

    private inline fun <reified T> stub(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as T

    override fun newCanvasImpl(): Canvas = stub()
    override fun newPaintImpl(): Paint = stub()
    override fun newBitmapImpl(width: Int, height: Int, format: Int): Bitmap = stub()
    override fun decodeBitmapImpl(inputStream: InputStream?): Bitmap = stub()
    override fun decodeBitmapImpl(inputStream: InputStream?, width: Int, height: Int, percent: Int): Bitmap = stub()
    override fun decodeSvgBitmapImpl(inputStream: InputStream?, width: Int, height: Int, percent: Int): Bitmap = stub()
    override fun loadBitmapAssetImpl(
        relativePathPrefix: String?,
        src: String?,
        resourceProvider: XmlThemeResourceProvider?,
        width: Int,
        height: Int,
        percent: Int,
        themeCallback: org.oscim.theme.ThemeCallback?,
    ): Bitmap = stub()
}
