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
 * A malformed theme draws a blank map rather than erroring, so parse it with VTM's own
 * parser. The null graphics backend is enough: the parser only asks it for paints and textures.
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

    /** The dark branch takes its own path through the generator. */
    @Test
    fun `the dark theme is a theme VTM accepts`() {
        assertNotNull(parse(darkXml))
    }

    /** A misspelled key still parses, so each key's presence is asserted. */
    @Test
    fun `the vocabulary the app relies on is present`() {
        listOf(
            "natural", "waterway", "landuse", "leisure", "building",
            "highway", "railway", "place",
        ).forEach { assertContains(lightXml, """k="$it"""") }
    }

    /** Undrawn, the land overlay shows through and a coastal map's ocean reads as land. */
    @Test
    fun `the sea is drawn`() {
        assertContains(lightXml, """k="natural" v="issea|sea"""")
        assertContains(lightXml, """k="natural" v="nosea"""")
    }

    /** Subway runs underground; drawing it draws a line over ground it never crosses. */
    @Test
    fun `surface rail is drawn and subway is not`() {
        assertContains(lightXml, "rail|light_rail|narrow_gauge")
        assert(!lightXml.contains("subway")) { "subway should not be drawn" }
    }

    /** Each file's land box is painted underneath, so uncovered ground reads as empty. */
    @Test
    fun `the background is transparent`() {
        assertContains(lightXml, """map-background="#00000000"""")
    }

    @Test
    fun `widths are filled in between stops`() {
        kotlin.test.assertEquals(
            listOf(12 to 0.5f, 13 to 1.0f, 14 to 1.5f, 15 to 3.0f, 16 to 4.5f, 17 to 6f),
            MapRenderTheme.perZoom(listOf(12 to 0.5f, 14 to 1.5f, 17 to 6f)),
        )
    }

    @Test
    fun `a footpath is narrower than the smallest road at every zoom`() {
        val roads = MapRenderTheme.perZoom(MapRenderTheme.MINOR_ROAD_STOPS).toMap()
        MapRenderTheme.perZoom(MapRenderTheme.PATH_STOPS).forEach { (zoom, width) ->
            val road = roads[zoom] ?: return@forEach
            assert(width < road) { "z$zoom: path $width, road $road" }
        }
        kotlin.test.assertEquals(3f, MapRenderTheme.PATH_STOPS.last().second)
    }

    /** VTM grows widths 1.4x per zoom above z12. */
    @Test
    fun `widths undo VTM's zoom growth`() {
        kotlin.test.assertEquals(2f, MapRenderTheme.unscaled(12, 2f))
        kotlin.test.assertEquals(2f, MapRenderTheme.unscaled(9, 2f))
        kotlin.test.assertEquals(1.02f, MapRenderTheme.unscaled(14, 2f))
    }

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
