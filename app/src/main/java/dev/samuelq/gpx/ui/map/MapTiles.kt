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

/** The generated theme, as the stream VTM insists on. */
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
 * How far in the camera may go. From the deepest zoom the file stores, not the one it
 * advertises: published files keep z14 tiles and claim z21.
 */
internal val OfflineMap.maxViewZoom: Int
    get() = (header.baseZoom + OVERZOOM_ALLOWANCE).coerceAtMost(Viewport.MAX_ZOOM_LEVEL)

/**
 * The maps as one source, each file's data cut to its own box: low-zoom tiles carry towns
 * far past the box, and names draw above the mask. Queries files directly because
 * [MultiMapFileTileSource] hands every file the same sink, losing which box an element is from.
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

private class ClippedMapData(private val files: List<Pair<MapFile, BoundingBox>>) : ITileDataSource {
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
 * Passes on what lies inside one box, in tile pixels. Also keeps each file's completion
 * from reaching the real sink once per file.
 */
private class ClippingSink(sink: ITileDataSink) : TileDataSink(sink) {
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
        // Most tiles are wholly inside and need no clipping.
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
