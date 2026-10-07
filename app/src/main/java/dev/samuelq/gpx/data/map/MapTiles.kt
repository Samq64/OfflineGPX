package dev.samuelq.gpx.data.map

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
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
import org.oscim.tiling.source.mapfile.header.SubFileParameter
import org.oscim.utils.geom.TileClipper

/** The generated theme, as the stream VTM insists on. */
internal class GeneratedRenderTheme(xml: String) : ThemeFile {
    private val bytes = xml.toByteArray()
    private var menuCallback: XmlRenderThemeMenuCallback? = null
    private var resourceProvider: XmlThemeResourceProvider? = null
    private var mapsforgeTheme = false

    override fun getMenuCallback(): XmlRenderThemeMenuCallback? = menuCallback
    override fun setMenuCallback(callback: XmlRenderThemeMenuCallback?) {
        menuCallback = callback
    }
    override fun getRelativePathPrefix(): String = ""
    override fun getRenderThemeAsStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getResourceProvider(): XmlThemeResourceProvider? = resourceProvider
    override fun setResourceProvider(provider: XmlThemeResourceProvider?) {
        resourceProvider = provider
    }

    // Set by the parser itself on seeing the mapsforge namespace.
    override fun isMapsforgeTheme(): Boolean = mapsforgeTheme
    override fun setMapsforgeTheme(value: Boolean) {
        mapsforgeTheme = value
    }
}

/**
 * How far in the camera may go. From the deepest zoom the file stores, not the one it
 * advertises: published files keep z14 tiles and claim z21.
 */
internal val OfflineMap.maxViewZoom: Int
    get() = (baseZoom + OVERZOOM_ALLOWANCE).coerceAtMost(Viewport.MAX_ZOOM_LEVEL)

/**
 * The maps as one source, each file's data cut to its own box: low-zoom tiles carry towns
 * far past the box, and names draw above the mask. Queries files directly because
 * [MultiMapFileTileSource] hands every file the same sink, losing which box an element is from.
 */
internal class ClippedMapSource(private val maps: List<OfflineMap>) : MultiMapFileTileSource() {
    private val sources = maps.map { map ->
        MapFileTileSource().apply { setMapFile(map.file.path) }.also { add(it) }
    }

    override fun getDataSource(): ITileDataSource {
        val opened = maps.zip(sources).mapNotNull { (map, source) ->
            try {
                OpenedMap(MapFile(source), map)
            } catch (_: IOException) {
                null
            }
        }
        return OverzoomTileDataSource(ClippedMapData(opened), overZoom)
    }
}

/** One file as a loader thread reads it; each thread gets its own handles. */
private class OpenedMap(val file: MapFile, val map: OfflineMap) {
    val box: BoundingBox = map.bounds

    private val indexes = HashMap<SubFileParameter, TileIndex?>()

    /**
     * Whether the writer marked the tile under tile pixel [x], [y] all water. True when
     * unknown, which keeps the sea as drawn.
     */
    fun isWater(tile: Tile, x: Float, y: Float): Boolean {
        val subFile = map.subFileFor(tile.zoomLevel.toInt())
        val index = indexes.getOrPut(subFile) {
            try {
                TileIndex(map.file, subFile)
            } catch (_: IOException) {
                null
            }
        } ?: return true
        // Pixel to its tile at the sub-file's base zoom, which may be above or below this one.
        val shift = subFile.baseZoomLevel - tile.zoomLevel
        val px = (tile.tileX * Tile.SIZE + x.toDouble()) / Tile.SIZE
        val py = (tile.tileY * Tile.SIZE + y.toDouble()) / Tile.SIZE
        val scale = 2.0.pow(shift)
        return try {
            index.isWater(floor(px * scale).toLong(), floor(py * scale).toLong())
        } catch (_: IOException) {
            true
        }
    }

    fun dispose() {
        file.dispose()
        indexes.values.forEach { it?.close() }
    }
}

private class ClippedMapData(private val files: List<OpenedMap>) : ITileDataSource {
    override fun query(tile: MapTile, sink: ITileDataSink) {
        val clipping = ClippingSink(sink)
        try {
            val covering = files.withIndex().filter { (_, opened) -> opened.file.supportsTile(tile) }
            clipping.merging = covering.size > 1
            covering.forEach { (i, opened) ->
                clipping.level = i + 1
                clipping.levels = files.size
                clipping.startFile(tile, opened.box)
                opened.file.query(tile, clipping)
                clipping.finishFile { x, y -> opened.isWater(tile, x, y) }
            }
            sink.completed(QueryResult.SUCCESS)
        } catch (_: Exception) {
            sink.completed(QueryResult.FAILED)
        }
    }

    override fun dispose() = files.forEach { it.dispose() }

    override fun cancel() = files.forEach { it.file.cancel() }
}

/** A rectangle in tile pixels. */
private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val coversTile get() = left <= 0f && top <= 0f && right >= Tile.SIZE && bottom >= Tile.SIZE

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** Sharing more than an edge. */
    fun overlaps(other: Rect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun inset(by: Float) = Rect(left + by, top + by, right - by, bottom - by)

    fun intersect(other: Rect): Rect? =
        Rect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
            .takeIf { it.left < it.right && it.top < it.bottom }

    /** What's left of this after [others], as cells of the grid their edges make. */
    fun minus(others: List<Rect>): List<Rect> {
        val xs = (others.flatMap { listOf(it.left, it.right) } + left + right)
            .filter { it in left..right }.distinct().sorted()
        val ys = (others.flatMap { listOf(it.top, it.bottom) } + top + bottom)
            .filter { it in top..bottom }.distinct().sorted()
        val cells = ArrayList<Rect>()
        for (i in 0 until xs.size - 1) {
            for (j in 0 until ys.size - 1) {
                val midX = (xs[i] + xs[i + 1]) / 2
                val midY = (ys[j] + ys[j + 1]) / 2
                if (others.none { it.contains(midX, midY) }) cells += Rect(xs[i], ys[j], xs[i + 1], ys[j + 1])
            }
        }
        return cells
    }
}

/**
 * Passes on what lies inside one file's box, in tile pixels. Also keeps each file's
 * completion from reaching the real sink once per file.
 *
 * A file's sea is held until it's read. Extracts lay a sea rectangle under each tile of their
 * index and draw the coast as land over it, from land polygons cut on a 1° grid, and some
 * lack a whole cell: roads then run through open water. So where a cell has features in the
 * rectangle but no land, the sea there is cut out, unless the index calls the tile all
 * water. Near-shore water has no features, so it stays.
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

    private class HeldSea(val element: MapElement, val bounds: Rect)

    private val heldSea = ArrayList<HeldSea>()
    private val land = ArrayList<Rect>()

    /** The file's other features' bounds, four floats each. */
    private var features = FloatArray(256)
    private var featureCount = 0

    /** Passes on the held sea that's real; [isWater] takes a point in tile pixels. */
    fun finishFile(isWater: (x: Float, y: Float) -> Boolean) {
        for (sea in heldSea) {
            val b = sea.bounds
            // Inset from the rectangle's edges, which a neighbour's features overhang.
            val inner = b.inset((b.right - b.left) * FEATURE_INSET)
            val overlap = gridOverlapPixels(b)
            val missing = gridCells(b).filter { cell ->
                land.none { it.overlaps(cell.inset(overlap)) } && hasFeatureIn(cell.intersect(inner))
            }
            when {
                missing.isEmpty() -> passOn(sea.element)
                isWater((b.left + b.right) / 2, (b.top + b.bottom) / 2) -> passOn(sea.element)
                // The sea is its tile's rectangle, so the parts are rectangles too.
                else -> b.minus(missing).forEach { passOn(sea.element.rectangle(it)) }
            }
        }
        heldSea.clear()
        land.clear()
        featureCount = 0
    }

    /** How far a neighbouring cell's land reaches over a grid line near [rect], in pixels. */
    private fun gridOverlapPixels(rect: Rect): Float {
        val latitude = pixelToLatitude((rect.top + rect.bottom) / 2)
        return abs(latitudeToPixel(latitude) - latitudeToPixel(latitude + GRID_OVERLAP_DEGREES))
    }

    /** [rect] cut on whole degrees, as the land polygons are. */
    private fun gridCells(rect: Rect): List<Rect> {
        val xs = gridLines(rect.left, rect.right, ::pixelToLongitude, ::longitudeToPixel)
        val ys = gridLines(rect.top, rect.bottom, ::pixelToLatitude, ::latitudeToPixel)
        val cells = ArrayList<Rect>((xs.size - 1) * (ys.size - 1))
        for (i in 0 until xs.size - 1) {
            for (j in 0 until ys.size - 1) {
                cells += Rect(xs[i], ys[j], xs[i + 1], ys[j + 1])
            }
        }
        return cells
    }

    private fun gridLines(
        from: Float,
        to: Float,
        toDegrees: (Float) -> Double,
        toPixel: (Double) -> Float,
    ): List<Float> {
        val a = toDegrees(from)
        val b = toDegrees(to)
        val lines = (ceil(minOf(a, b)).toInt()..floor(maxOf(a, b)).toInt())
            .map { toPixel(it.toDouble()) }
            .filter { it > from && it < to }
        return (listOf(from) + lines.sorted() + to)
    }

    private var tileX = 0.0
    private var tileY = 0.0
    private var worldPixels = 0.0

    private fun pixelToLongitude(x: Float) = MercatorProjection.toLongitude((tileX + x) / worldPixels)
    private fun pixelToLatitude(y: Float) = MercatorProjection.toLatitude((tileY + y) / worldPixels)
    private fun longitudeToPixel(longitude: Double) =
        (MercatorProjection.longitudeToX(longitude) * worldPixels - tileX).toFloat()
    private fun latitudeToPixel(latitude: Double) =
        (MercatorProjection.latitudeToY(latitude) * worldPixels - tileY).toFloat()

    private fun hasFeatureIn(rect: Rect?): Boolean {
        if (rect == null) return false
        for (i in 0 until featureCount step 4) {
            if (features[i] < rect.right && features[i + 2] > rect.left &&
                features[i + 1] < rect.bottom && features[i + 3] > rect.top
            ) {
                return true
            }
        }
        return false
    }

    private fun addFeature(element: MapElement) {
        if (featureCount + 4 > features.size) features = features.copyOf(features.size * 2)
        val b = element.bounds()
        features[featureCount++] = b.left
        features[featureCount++] = b.top
        features[featureCount++] = b.right
        features[featureCount++] = b.bottom
    }

    fun startFile(tile: Tile, bounds: BoundingBox) {
        if (keys.isNotEmpty()) {
            earlierBoxes += box
            earlierKeys += keys
            keys.clear()
        }
        val scale = Tile.SIZE.toDouble() * (1 shl tile.zoomLevel.toInt())
        worldPixels = scale
        tileX = tile.tileX.toDouble() * Tile.SIZE
        tileY = tile.tileY.toDouble() * Tile.SIZE
        box = Rect(
            left = (MercatorProjection.longitudeToX(bounds.minLongitude) * scale - tile.tileX * Tile.SIZE).toFloat(),
            top = (MercatorProjection.latitudeToY(bounds.maxLatitude) * scale - tile.tileY * Tile.SIZE).toFloat(),
            right = (MercatorProjection.longitudeToX(bounds.maxLongitude) * scale - tile.tileX * Tile.SIZE).toFloat(),
            bottom = (MercatorProjection.latitudeToY(bounds.minLatitude) * scale - tile.tileY * Tile.SIZE).toFloat(),
        )
        uncovered = if (earlierBoxes.isEmpty()) listOf(box) else box.minus(earlierBoxes)
    }

    override fun process(element: MapElement) {
        when (element.tags.getValue("natural")) {
            "sea" -> {
                heldSea += HeldSea(MapElement(element), element.bounds())
                return
            }
            "nosea" -> land += element.bounds()
            else -> if (element.pointNextPos >= 2) addFeature(element)
        }
        passOn(element)
    }

    private fun passOn(element: MapElement) {
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

/**
 * This element's tags and draw order on [rect]. Built rather than clipped from a copy:
 * clipped copies drew above the land.
 */
private fun MapElement.rectangle(rect: Rect) = MapElement().also {
    it.tags.set(tags.asArray())
    it.setLayer(layer)
    it.level = level
    it.startPolygon()
    it.addPoint(rect.left, rect.top)
    it.addPoint(rect.right, rect.top)
    it.addPoint(rect.right, rect.bottom)
    it.addPoint(rect.left, rect.bottom)
}

private fun MapElement.bounds(): Rect {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (i in 0 until pointNextPos step 2) {
        left = minOf(left, points[i])
        right = maxOf(right, points[i])
        top = minOf(top, points[i + 1])
        bottom = maxOf(bottom, points[i + 1])
    }
    return Rect(left, top, right, bottom)
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

/** Of a sea rectangle's width. */
private const val FEATURE_INSET = 0.1f

/** Land polygons are cut on the grid with a little overlap; measured at 0.0005°. */
private const val GRID_OVERLAP_DEGREES = 0.002

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L

/** How far past a file's deepest zoom the camera may still go. */
private const val OVERZOOM_ALLOWANCE = 4
