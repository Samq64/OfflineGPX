package dev.samuelq.gpx.ui.map

import dev.samuelq.gpx.data.map.OfflineMap
import org.oscim.core.BoundingBox
import org.oscim.core.MapElement
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import org.oscim.layers.tile.MapTile
import org.oscim.map.Viewport
import org.oscim.theme.ThemeFile
import org.oscim.theme.XmlRenderThemeMenuCallback
import org.oscim.theme.XmlThemeResourceProvider
import org.oscim.tiling.ITileDataSink
import org.oscim.tiling.ITileDataSource
import org.oscim.tiling.OverzoomTileDataSource
import org.oscim.tiling.QueryResult
import org.oscim.tiling.TileDataSink
import org.oscim.tiling.source.mapfile.MapFile
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import org.oscim.utils.geom.TileClipper
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The generated theme, handed to VTM as the stream it insists on. */
internal class GeneratedRenderTheme(xml: String) : ThemeFile {
    private val bytes = xml.toByteArray()
    private var menuCallback: XmlRenderThemeMenuCallback? = null
    private var resourceProvider: XmlThemeResourceProvider? = null
    private var mapsforgeTheme = false

    override fun getMenuCallback(): XmlRenderThemeMenuCallback? = menuCallback
    override fun setMenuCallback(callback: XmlRenderThemeMenuCallback?) { menuCallback = callback }
    override fun getRelativePathPrefix(): String = ""
    override fun getRenderThemeAsStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getResourceProvider(): XmlThemeResourceProvider? = resourceProvider
    override fun setResourceProvider(provider: XmlThemeResourceProvider?) { resourceProvider = provider }
    // Set by the parser itself on seeing the mapsforge namespace.
    override fun isMapsforgeTheme(): Boolean = mapsforgeTheme
    override fun setMapsforgeTheme(value: Boolean) { mapsforgeTheme = value }
}

/** Whether VTM can read this file at all. Opened and closed again; the tile layer reopens it. */
internal fun OfflineMap.opens(): Boolean = MapFileTileSource().run {
    setMapFile(file.path)
    open().isSuccess.also { close() }
}

/**
 * How far in the camera may go over this map. Measured from the deepest zoom the file
 * *stores*, not the one it advertises: a published file keeps tiles at z14 and claims
 * z21, and everything past the base zoom is that tile's geometry drawn bigger.
 */
internal val OfflineMap.maxViewZoom: Int
    get() = (header.baseZoom + OVERZOOM_ALLOWANCE).coerceAtMost(Viewport.MAX_ZOOM_LEVEL)

/**
 * The maps as one source, each file's data cut to its own box. A file's low zooms are
 * whole tiles tens of kilometres wide, so it carries lakes, roads and towns well past its
 * box - painted over by the mask, but names are drawn above that, and a town floating on
 * empty ground reads as broken.
 *
 * Queries the files itself rather than through [MultiMapFileTileSource]'s own data source,
 * which hands every file the same sink and so can't say which box an element belongs to.
 * No de-duplication across files: they don't overlap, and what two share past their
 * edges is cut away here.
 */
internal class ClippedMapSource(maps: List<OfflineMap>) : MultiMapFileTileSource() {
    private val files = maps.map { map ->
        val h = map.header
        val source = MapFileTileSource().apply { setMapFile(map.file.path) }
        add(source)
        source to BoundingBox(h.minLatitude, h.minLongitude, h.maxLatitude, h.maxLongitude)
    }

    override fun getDataSource(): ITileDataSource {
        val opened = files.mapNotNull { (source, box) ->
            try {
                MapFile(source) to box
            } catch (_: IOException) {
                null
            }
        }
        return OverzoomTileDataSource(ClippedMapData(opened), overZoom)
    }
}

internal class ClippedMapData(private val files: List<Pair<MapFile, BoundingBox>>) : ITileDataSource {
    override fun query(tile: MapTile, sink: ITileDataSink) {
        val clipping = ClippingSink(sink)
        try {
            files.forEachIndexed { i, (file, box) ->
                if (!file.supportsTile(tile)) return@forEachIndexed
                clipping.level = i + 1
                clipping.levels = files.size
                clipping.clipTo(tile, box)
                file.query(tile, clipping)
            }
            sink.completed(QueryResult.SUCCESS)
        } catch (_: Exception) {
            sink.completed(QueryResult.FAILED)
        }
    }

    override fun dispose() = files.forEach { it.first.dispose() }

    override fun cancel() = files.forEach { it.first.cancel() }
}

/**
 * Passes on what lies inside one box, in the tile's own pixels. A [TileDataSink] because
 * that is what a [MapFile] expects to be handed, and it keeps the file's own completion
 * from reaching the real sink once per file.
 */
internal class ClippingSink(sink: ITileDataSink) : TileDataSink(sink) {
    private val clipper = TileClipper(0f, 0f, 0f, 0f)
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f
    private var whole = false

    fun clipTo(tile: Tile, box: BoundingBox) {
        val scale = Tile.SIZE.toDouble() * (1 shl tile.zoomLevel.toInt())
        left = (MercatorProjection.longitudeToX(box.minLongitude) * scale - tile.tileX * Tile.SIZE).toFloat()
        right = (MercatorProjection.longitudeToX(box.maxLongitude) * scale - tile.tileX * Tile.SIZE).toFloat()
        top = (MercatorProjection.latitudeToY(box.maxLatitude) * scale - tile.tileY * Tile.SIZE).toFloat()
        bottom = (MercatorProjection.latitudeToY(box.minLatitude) * scale - tile.tileY * Tile.SIZE).toFloat()
        // Most tiles are wholly inside, and those need no clipping at all.
        whole = left <= 0f && top <= 0f && right >= Tile.SIZE && bottom >= Tile.SIZE
        clipper.setRect(left, top, right, bottom)
    }

    override fun process(element: MapElement) {
        if (!whole) {
            if (element.isPoint) {
                if (!inside(element.getPointX(0), element.getPointY(0))) return
            } else if (!clipper.clip(element)) {
                return
            }
            element.labelPosition?.let { if (!inside(it.x, it.y)) element.labelPosition = null }
            element.centroidPosition?.let { if (!inside(it.x, it.y)) element.centroidPosition = null }
        }
        super.process(element)
    }

    private fun inside(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** How far past a file's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 4
