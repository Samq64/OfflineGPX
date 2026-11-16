package dev.samuelq.gpx.ui.map

import androidx.compose.ui.unit.IntSize
import dev.samuelq.gpx.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.oscim.core.BoundingBox
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile

class MapRotationTest {

    private val scale = (1 shl 14).toDouble()
    private val mapSize = Tile.SIZE * scale
    private val bearings = listOf(-170f, -90f, -33f, 0f, 12f, 45f, 90f, 135f, 180f)

    private fun position(bearing: Float) = MapPosition(51.5, -0.1, scale).apply { this.bearing = bearing }

    @Test
    fun `a heading of east puts east at the top`() {
        // Heading-up negates the heading, as VTM's own compass does.
        val position = position(-90f)
        val east = TrackPoint(51.5, MercatorProjection.toLongitude(position.x + 100 / mapSize))
        val at = screenPosition(east, position, 400, 800)
        assertEquals(200f, at.x, 0.01f)
        assertEquals(300f, at.y, 0.01f)
    }

    @Test
    fun `screen to map and back is the identity at any bearing`() {
        for (bearing in bearings) {
            val position = position(bearing)
            val (x, y) = mapPixel(37f, 611f, position, 400, 800)
            val point =
                TrackPoint(MercatorProjection.toLatitude(y / mapSize), MercatorProjection.toLongitude(x / mapSize))
            val back = screenPosition(point, position, 400, 800)
            assertEquals(37f, back.x, 0.01f, "at $bearing")
            assertEquals(611f, back.y, 0.01f, "at $bearing")
        }
    }

    @Test
    fun `the visible box holds every corner of a turned view`() {
        for (bearing in bearings) {
            val position = position(bearing)
            val box = position.visibleBox(IntSize(400, 800))
            for ((sx, sy) in listOf(0f to 0f, 400f to 0f, 0f to 800f, 400f to 800f)) {
                val (x, y) = mapPixel(sx, sy, position, 400, 800)
                val latitude = MercatorProjection.toLatitude(y / mapSize)
                val longitude = MercatorProjection.toLongitude(x / mapSize)
                assertTrue(latitude in box.minLatitude - 1e-6..box.maxLatitude + 1e-6, "$bearing $sx $sy")
                assertTrue(longitude in box.minLongitude - 1e-6..box.maxLongitude + 1e-6, "$bearing $sx $sy")
            }
        }
    }

    @Test
    fun `a quarter turn swaps the view's sides`() {
        val extent = BoundingBox(50.0, -2.0, 54.0, 2.0)
        val upright = centreLimit(extent, Insets(0, 0, 0, 0), 800, 400, scale)
        val turned = centreLimit(extent, Insets(0, 0, 0, 0), 400, 800, scale, bearing = 90f)
        assertEquals(upright.xmin, turned.xmin, 1e-12)
        assertEquals(upright.ymin, turned.ymin, 1e-12)
        assertEquals(upright.xmax, turned.xmax, 1e-12)
        assertEquals(upright.ymax, turned.ymax, 1e-12)
    }

    @Test
    fun `the limit changes smoothly as the view turns`() {
        val extent = BoundingBox(50.0, -2.0, 54.0, 2.0)
        val cover = Insets(0, 0, 0, 300)
        val upright = centreLimit(extent, cover, 400, 800, scale)
        val nudged = centreLimit(extent, cover, 400, 800, scale, bearing = 0.01f)
        assertEquals(upright.xmin, nudged.xmin, 1e-7)
        assertEquals(upright.ymax, nudged.ymax, 1e-7)
    }

    @Test
    fun `a turned limit keeps the uncovered part's centre inside the extent`() {
        val extent = BoundingBox(50.0, -2.0, 54.0, 2.0)
        val cover = Insets(100, 0, 0, 300)
        for (bearing in bearings) {
            val limit = centreLimit(extent, cover, 400, 800, scale, bearing)
            val corner = MapPosition().apply {
                x = limit.xmin
                y = limit.ymin
                this.scale = this@MapRotationTest.scale
                this.bearing = bearing
            }
            // The uncovered part's centre on screen, back on the map.
            val (x, y) = mapPixel(250f, 250f, corner, 400, 800)
            assertTrue(x / mapSize >= MercatorProjection.longitudeToX(-2.0), "$bearing")
            assertTrue(y / mapSize >= MercatorProjection.latitudeToY(54.0), "$bearing")
        }
    }

    @Test
    fun `a turned fit keeps the target's corners on screen, centred in the uncovered part`() {
        val target = BoundingBox(51.0, -1.0, 52.0, 1.0)
        val insets = Insets(0, 100, 0, 300)
        for (bearing in bearings) {
            val position = fit(target, IntSize(400, 400), insets, Double.MAX_VALUE, bearing)
            assertEquals(bearing, position.bearing)
            val corners = listOf(
                TrackPoint(51.0, -1.0),
                TrackPoint(51.0, 1.0),
                TrackPoint(52.0, -1.0),
                TrackPoint(52.0, 1.0),
            ).map { screenPosition(it, position, 400, 800) }
            for (at in corners) {
                assertTrue(at.x in -0.5f..400.5f && at.y in 99.5f..500.5f, "$bearing $at")
            }
            val middle = MapPosition().apply {
                x = MercatorProjection.longitudeToX(0.0)
                y = (MercatorProjection.latitudeToY(51.0) + MercatorProjection.latitudeToY(52.0)) / 2
            }
            val centre = screenPosition(
                TrackPoint(MercatorProjection.toLatitude(middle.y), MercatorProjection.toLongitude(middle.x)),
                position,
                400,
                800,
            )
            assertEquals(200f, centre.x, 0.5f)
            assertEquals(300f, centre.y, 0.5f)
        }
    }

    @Test
    fun `centring in the insets moves along the turned axes`() {
        val position = position(-90f)
        val before = MapPosition().apply { copy(position) }
        position.centreIn(Insets(0, 0, 0, 200))
        val target = TrackPoint(MercatorProjection.toLatitude(before.y), MercatorProjection.toLongitude(before.x))
        val at = screenPosition(target, position, 400, 800)
        // Halfway down the uncovered 600 px.
        assertEquals(200f, at.x, 0.01f)
        assertEquals(300f, at.y, 0.01f)
    }

    @Test
    fun `a fit wraps a bearing past a half turn`() {
        val position =
            fit(BoundingBox(51.0, -1.0, 52.0, 1.0), IntSize(400, 400), Insets(0, 0, 0, 0), Double.MAX_VALUE, -300f)
        assertEquals(60f, position.bearing, 1e-4f)
    }
}
