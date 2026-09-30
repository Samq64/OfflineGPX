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
            val covering = files.withIndex().filter { (_, entry) -> entry.first.supportsTile(tile) }
            clipping.merging = covering.size > 1
            covering.forEach { (i, entry) ->
                val (file, box) = entry
                clipping.level = i + 1
                clipping.levels = files.size
                clipping.startFile(tile, box)
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

/** A rectangle in tile pixels. */
private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val coversTile get() = left <= 0f && top <= 0f && right >= Tile.SIZE && bottom >= Tile.SIZE

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** What's left of this after [others], as cells of the grid their edges make. */
    fun minus(others: List<Rect>): List<Rect> {
        val xs = (others.flatMap { listOf(it.left, it.right) } + left + right)
            .filter { it in left..right }.distinct().sorted()
        val ys = (others.flatMap { listOf(it.top, it.bottom) } + top + bottom)
            .filter { it in top..bottom }.distinct().sorted()
        val cells = ArrayList<Rect>()
        for (i in 0 until xs.size - 1) for (j in 0 until ys.size - 1) {
            val midX = (xs[i] + xs[i + 1]) / 2
            val midY = (ys[j] + ys[j + 1]) / 2
            if (others.none { it.contains(midX, midY) }) cells += Rect(xs[i], ys[j], xs[i + 1], ys[j + 1])
        }
        return cells
    }
}

/**
 * Passes on what lies inside one file's box, in tile pixels. Also keeps each file's
 * completion from reaching the real sink once per file.
 *
 * Where boxes overlap, a feature an earlier file already gave, like a park on a shared
 * border, is passed on only outside the earlier files' boxes: two copies of an area cancel
 * out when filled.
 */
private class ClippingSink(sink: ITileDataSink) : TileDataSink(sink) {
    /** More than one file covers the tile, so copies are possible. */
    var merging = false

    private val clipper = TileClipper(0f, 0f, 0f, 0f)
    private var box = Rect(0f, 0f, 0f, 0f)

    /** Boxes of the files already passed on in this tile. */
    private val earlierBoxes = ArrayList<Rect>()

    /** [box] less [earlierBoxes], where copies may still go. */
    private var uncovered: List<Rect> = emptyList()

    private val earlierKeys = HashSet<Long>()
    private val keys = HashSet<Long>()

    fun startFile(tile: Tile, bounds: BoundingBox) {
        if (keys.isNotEmpty()) {
            earlierBoxes += box
            earlierKeys += keys
            keys.clear()
        }
        val scale = Tile.SIZE.toDouble() * (1 shl tile.zoomLevel.toInt())
        box = Rect(
            left = (MercatorProjection.longitudeToX(bounds.minLongitude) * scale - tile.tileX * Tile.SIZE).toFloat(),
            top = (MercatorProjection.latitudeToY(bounds.maxLatitude) * scale - tile.tileY * Tile.SIZE).toFloat(),
            right = (MercatorProjection.longitudeToX(bounds.maxLongitude) * scale - tile.tileX * Tile.SIZE).toFloat(),
            bottom = (MercatorProjection.latitudeToY(bounds.minLatitude) * scale - tile.tileY * Tile.SIZE).toFloat(),
        )
        uncovered = if (earlierBoxes.isEmpty()) listOf(box) else box.minus(earlierBoxes)
    }

    override fun process(element: MapElement) {
        if (!merging) {
            passWithin(element, box)
            return
        }
        val key = element.key()
        keys += key
        if (key !in earlierKeys) {
            passWithin(element, box)
            return
        }
        uncovered.forEachIndexed { i, cell ->
            passWithin(if (i == uncovered.lastIndex) element else MapElement(element), cell)
        }
    }

    private fun passWithin(element: MapElement, rect: Rect) {
        // Most tiles are wholly inside and need no clipping.
        if (!rect.coversTile) {
            if (element.isPoint) {
                if (!rect.contains(element.getPointX(0), element.getPointY(0))) return
            } else {
                clipper.setRect(rect.left, rect.top, rect.right, rect.bottom)
                if (!clipper.clip(element)) return
            }
            element.labelPosition?.let { if (!rect.contains(it.x, it.y)) element.labelPosition = null }
            element.centroidPosition?.let { if (!rect.contains(it.x, it.y)) element.centroidPosition = null }
        }
        super.process(element)
    }
}

/** Identity of an element as a file gives it, before clipping. */
private fun MapElement.key(): Long {
    var h = FNV_OFFSET
    fun mix(value: Long) {
        h = (h xor value) * FNV_PRIME
    }
    mix(type.ordinal.toLong())
    mix(layer.toLong())
    for (i in 0 until tags.size()) {
        mix(tags[i].key.hashCode().toLong())
        mix(tags[i].value.hashCode().toLong())
    }
    for (i in 0 until pointNextPos) mix(points[i].toRawBits().toLong())
    for (i in index) {
        if (i < 0) break
        mix(i.toLong())
    }
    return h
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L

/** How far past a file's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 4
