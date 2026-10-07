package dev.samuelq.gpx.data.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.oscim.backend.canvas.Bitmap
import org.oscim.core.BoundingBox
import org.oscim.core.MapElement
import org.oscim.core.Tag
import org.oscim.core.Tile
import org.oscim.tiling.ITileDataSink
import org.oscim.tiling.QueryResult

/**
 * One z8 tile, lon 0 to 0.70 and lat 46.6 to 47.0: the 1° grid cuts it 0.711 across and 0.042
 * down, in fractions of [Tile.SIZE]. Its bottom-left cell is the big one.
 */
class ClippingSinkTest {

    private class Collected : ITileDataSink {
        val elements = ArrayList<MapElement>()

        override fun process(element: MapElement) {
            elements += MapElement(element)
        }

        override fun setTileImage(bitmap: Bitmap) = Unit

        override fun completed(result: QueryResult) = Unit
    }

    /** In fractions of the tile. */
    private fun shape(tag: Tag, left: Float, top: Float, right: Float, bottom: Float, polygon: Boolean = true) =
        MapElement().apply {
            tags.add(tag)
            if (polygon) startPolygon() else startLine()
            addPoint(left * S, top * S)
            addPoint(right * S, top * S)
            if (polygon) addPoint(right * S, bottom * S)
            addPoint((if (polygon) left else right) * S, bottom * S)
        }

    private val sea get() = shape(Tag("natural", "sea"), 0f, 0f, 1f, 1f)
    private val road get() = shape(Tag("highway", "path"), 0.2f, 0.4f, 0.24f, 0.44f, polygon = false)
    private fun land(left: Float) = shape(Tag("natural", "nosea"), left, 0f, 1f, 1f)

    /** The sea elements passed on, after the file's [elements] and its water flag. */
    private fun seaAfter(vararg elements: MapElement, allWater: Boolean = false): List<MapElement> {
        val out = Collected()
        ClippingSink(out).apply {
            startFile(Tile(128, 90, 8), BoundingBox(-85.0, -180.0, 85.0, 180.0))
            elements.forEach(::process)
            finishFile { _, _ -> allWater }
        }
        return out.elements.filter { it.tags.getValue("natural") == "sea" }
    }

    private fun List<MapElement>.cover(x: Float, y: Float) = any { it.contains(x * S, y * S) }

    private fun MapElement.contains(x: Float, y: Float): Boolean {
        val xs = (0 until pointNextPos step 2).map { points[it] }
        val ys = (0 until pointNextPos step 2).map { points[it + 1] }
        return x > xs.min() && x < xs.max() && y > ys.min() && y < ys.max()
    }

    @Test
    fun `sea is cut out of a cell with features but no land`() {
        val sea = seaAfter(sea, land(0.8f), road)

        assertFalse(sea.cover(0.22f, 0.42f), "under the road")
        assertTrue(sea.cover(0.9f, 0.42f), "beside the land")
        assertTrue(sea.cover(0.22f, 0.02f), "the cell above, which has no features")
    }

    @Test
    fun `sea with no features in it stays whole`() {
        val sea = seaAfter(sea, land(0.8f))
        assertEquals(1, sea.size)
        assertTrue(sea.cover(0.22f, 0.42f))
    }

    @Test
    fun `a tile the index calls all water keeps its sea`() {
        val sea = seaAfter(sea, land(0.8f), road, allWater = true)
        assertEquals(1, sea.size)
        assertTrue(sea.cover(0.22f, 0.42f))
    }

    @Test
    fun `sea under land in every cell stays whole`() {
        val sea = seaAfter(sea, land(0f), road)
        assertEquals(1, sea.size)
    }

    private companion object {
        val S = Tile.SIZE.toFloat()
    }
}
