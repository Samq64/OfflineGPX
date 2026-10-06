package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.core.model.TrackPoint
import org.oscim.core.BoundingBox
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapCameraTest {

    private val none = Insets(0, 0, 0, 0)
    private val scale = (1 shl 14).toDouble()
    private val mapSize = Tile.SIZE * scale

    @Test
    fun `the camera's position is the view's centre`() {
        val position = MapPosition(51.5, -0.1, scale)
        val centre = screenPosition(TrackPoint(51.5, -0.1), position, 400, 800)
        assertEquals(200f, centre.x, 0.01f)
        assertEquals(400f, centre.y, 0.01f)

        // A map pixel east and south is a screen pixel right and down.
        val east = TrackPoint(51.5, MercatorProjection.toLongitude(position.x + 10 / mapSize))
        assertEquals(210f, screenPosition(east, position, 400, 800).x, 0.01f)
    }

    @Test
    fun `the visible box is centred on the camera`() {
        val position = MapPosition(10.0, 20.0, scale)
        val box = position.visibleBox(IntSize(512, 256))

        // BoundingBox holds microdegrees.
        assertEquals(20.0, (box.minLongitude + box.maxLongitude) / 2, 1e-6)
        assertEquals(512 / mapSize * 360, box.longitudeSpan, 2e-6)
        assertTrue(box.minLatitude < 10.0 && box.maxLatitude > 10.0)
    }

    @Test
    fun `usable space is what the insets leave, if any`() {
        val insets = Insets(10, 20, 30, 40)
        assertEquals(IntSize(60, 40), IntSize(100, 100).usable(insets))
        assertNull(IntSize(40, 100).usable(insets))
        assertNull(IntSize(100, 60).usable(insets))
        assertNull((null as IntSize?).usable(insets))
    }

    @Test
    fun `padding becomes physical pixel insets, mirrored in RTL`() {
        val padding = PaddingValues(start = 1.dp, top = 2.dp, end = 3.dp, bottom = 4.dp)
        assertEquals(Insets(2, 4, 6, 8), padding.toInsets(Density(2f), LayoutDirection.Ltr))
        assertEquals(Insets(6, 4, 2, 8), padding.toInsets(Density(2f), LayoutDirection.Rtl))
    }

    @Test
    fun `including widens a box, or leaves it`() {
        val box = BoundingBox(0.0, 0.0, 1.0, 1.0)
        assertSame(box, box.including(null))
        val wider = box.including(BoundingBox(-1.0, 2.0, 0.5, 3.0))
        assertEquals(-1.0, wider.minLatitude, 1e-6)
        assertEquals(3.0, wider.maxLongitude, 1e-6)
    }

    @Test
    fun `a fit centres the target in the uncovered part of the view`() {
        val target = BoundingBox(51.0, -1.0, 52.0, 1.0)
        val insets = Insets(0, 100, 0, 300)
        val usable = IntSize(400, 400)
        val position = fit(target, usable, insets, maxScale = Double.MAX_VALUE)

        val centre = screenPosition(TrackPoint(51.5, 0.0), position, 400, 800)
        // Halfway down the uncovered band, 100 to 500.
        assertEquals(300f, centre.y, 2f)
        assertEquals(200f, centre.x, 0.5f)
        val west = screenPosition(TrackPoint(51.5, -1.0), position, 400, 800)
        assertTrue(west.x >= -0.5f, "$west")
    }

    @Test
    fun `tracks are too far apart when framing them together leaves only specks`() {
        val ottawa = BoundingBox(45.415, -75.701, 45.439, -75.699)
        val usable = IntSize(1080, 1900)
        val minPx = 42f
        // Alone, a track fills the view.
        assertFalse(tooFarApart(ottawa, listOf(ottawa), usable, Double.MAX_VALUE, minPx))
        // A few km apart, still lines.
        val nearby = BoundingBox(45.380, -75.750, 45.404, -75.748)
        assertFalse(tooFarApart(ottawa.extendBoundingBox(nearby), listOf(ottawa, nearby), usable, Double.MAX_VALUE, minPx))
        // Ottawa and Sydney, or Ottawa and Kingston, both specks.
        val sydney = BoundingBox(-33.870, 151.209, -33.846, 151.211)
        assertTrue(tooFarApart(ottawa.extendBoundingBox(sydney), listOf(ottawa, sydney), usable, Double.MAX_VALUE, minPx))
        val kingston = BoundingBox(44.230, -76.481, 44.254, -76.479)
        assertTrue(tooFarApart(ottawa.extendBoundingBox(kingston), listOf(ottawa, kingston), usable, Double.MAX_VALUE, minPx))
        // One long ride among them is enough to frame.
        val ride = BoundingBox(44.3, -76.4, 45.4, -75.7)
        assertFalse(
            tooFarApart(ottawa.extendBoundingBox(kingston), listOf(ottawa, kingston, ride), usable, Double.MAX_VALUE, minPx),
        )
    }

    @Test
    fun `tiny tracks at the zoom cap aren't too far apart`() {
        val a = BoundingBox(51.5, 0.0, 51.50001, 0.00001)
        val b = BoundingBox(51.5001, 0.0001, 51.50011, 0.00011)
        assertFalse(tooFarApart(a.extendBoundingBox(b), listOf(a, b), IntSize(400, 400), maxScale = scale, minPx = 42f))
    }

    @Test
    fun `a fit to a tiny box stops at the zoom cap`() {
        val position = fit(BoundingBox(51.5, 0.0, 51.5001, 0.0001), IntSize(400, 400), none, maxScale = scale)
        assertEquals(scale, position.scale)
    }

    @Test
    fun `the camera centre may go as far as keeps the extent's edges at the screen's`() {
        val extent = BoundingBox(50.0, -2.0, 54.0, 2.0)
        val limit = centreLimit(extent, none, 400, 800, scale)

        val west = MercatorProjection.longitudeToX(-2.0)
        val north = MercatorProjection.latitudeToY(54.0)
        assertEquals(west + 200 / mapSize, limit.xmin, 1e-12)
        assertEquals(north + 400 / mapSize, limit.ymin, 1e-12)
        assertTrue(limit.xmin < limit.xmax && limit.ymin < limit.ymax)
    }

    @Test
    fun `a bottom cover lets the extent's south edge rise above it`() {
        val extent = BoundingBox(50.0, -2.0, 54.0, 2.0)
        val open = centreLimit(extent, none, 400, 800, scale)
        val covered = centreLimit(extent, Insets(0, 0, 0, 300), 400, 800, scale)

        assertEquals(open.ymax + 300 / mapSize, covered.ymax, 1e-12)
        assertEquals(open.ymin, covered.ymin, 1e-12)
    }

    @Test
    fun `an extent narrower than the view is held centred`() {
        val extent = BoundingBox(51.5, -0.001, 51.501, 0.001)
        val limit = centreLimit(extent, Insets(100, 0, 0, 0), 400, 800, scale)

        assertEquals(limit.xmin, limit.xmax)
        assertEquals(limit.ymin, limit.ymax)
        // Centred in what the left cover leaves: 50 px right of the extent's middle.
        val middle = MercatorProjection.longitudeToX(0.0)
        assertEquals(middle - 50 / mapSize, limit.xmin, 1e-12)
    }

    @Test
    fun `a nudge pans the least that brings the point inside the margin`() {
        val insets = Insets(10, 20, 30, 40)
        assertNull(nudge(Offset(200f, 300f), 400, 800, insets, margin = 5))
        assertEquals(15.0 to 0.0, nudge(Offset(0f, 300f), 400, 800, insets, margin = 5))
        assertEquals(-5.0 to 0.0, nudge(Offset(370f, 300f), 400, 800, insets, margin = 5))
        assertEquals(0.0 to 25.0, nudge(Offset(200f, 0f), 400, 800, insets, margin = 5))
        assertEquals(-15.0 to -10.0, nudge(Offset(380f, 765f), 400, 800, insets, margin = 5))
    }

    @Test
    fun `no nudge when the insets leave no room`() {
        assertNull(nudge(Offset(-100f, -100f), 100, 800, Insets(50, 0, 50, 0), margin = 0))
        assertNull(nudge(Offset(-100f, -100f), 400, 100, Insets(0, 60, 0, 40), margin = 0))
    }
}
