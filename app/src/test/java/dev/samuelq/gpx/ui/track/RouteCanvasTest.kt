package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.core.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection and the fit, which between them decide how much of the window a route
 * gets to use.
 */
class RouteCanvasTest {

    private val density = Density(1f)
    private val noPadding = PaddingValues(0.dp)

    private fun points(vararg coordinates: Pair<Double, Double>): List<TrackPoint> =
        coordinates.map { (lat, lon) -> TrackPoint(latitude = lat, longitude = lon) }

    private fun pathOf(points: List<TrackPoint>): RoutePath =
        routePathOf(points, intArrayOf(0), GeoBounds.of(points)!!)!!

    @Test
    fun `a route running due north is a sliver in unit space`() {
        val extent = pathOf(points(50.0 to -1.0, 50.01 to -1.0)).extent

        assertTrue("north-south should fill the long side", extent.height > 0.99f)
        assertTrue("east-west should be nothing at all", extent.width < 0.001f)
    }

    /**
     * The longer side fills the unit square and the shorter keeps its true proportion -
     * including the cos(latitude) correction, without which this box would come out square
     * and the shape would be a third too wide at 50N.
     */
    @Test
    fun `the long side fills the unit square and the short side keeps its proportion`() {
        val extent = pathOf(
            points(50.0 to -1.0, 50.01 to -1.0, 50.01 to -0.98, 50.0 to -0.98)
        ).extent

        // 0.02 degrees of longitude at 50N is about 0.0129 of ground, against 0.01 of
        // latitude: wider than it is tall, by the cosine.
        assertEquals(1f, extent.width, 0.001f)
        assertEquals(0.778f, extent.height, 0.005f)
    }

    @Test
    fun `one point has no extent to scale to`() {
        assertTrue(pathOf(points(50.0 to -1.0)).extent.isDegenerate)
    }

    /**
     * The regression this was written for: a tall thin route used to be fitted to the
     * *square* its sliver sits in, so on a phone it got the narrow side of the window and
     * spent the rest on the empty ground either side of the line.
     */
    @Test
    fun `a tall route is fitted to the tall side of a tall window`() {
        val path = pathOf(points(50.0 to -1.0, 50.01 to -1.0))
        val viewport = Size(400f, 1000f)

        // Read the fit back through the one public thing that uses it: asking to follow a
        // point already on screen returns the camera untouched, so walk the zoom up until
        // it doesn't, and that is the point leaving the viewport.
        val top = followingCamera(
            path = path,
            sourceIndex = 0,
            camera = MapCamera.Fitted,
            viewport = viewport,
            contentPadding = noPadding,
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            extent = routeExtentOf(listOf(RouteLayer(1L, path, Color.Red))),
        )

        // Fitted to the height, both ends sit hard against the top and bottom edges, so
        // the follow margin has to pull the camera to bring the end point clear. Fitted to
        // the width they would be 300px inside the viewport and nothing would move.
        assertTrue("the fit should reach the edges of the tall axis", top !== MapCamera.Fitted)
    }

    @Test
    fun `the extent of several routes is what they cover between them`() {
        val north = pathOf(points(50.0 to -1.0, 50.01 to -1.0))
        val extent = routeExtentOf(
            listOf(
                RouteLayer(1L, north, Color.Red),
                RouteLayer(2L, north, Color.Blue),
            )
        )

        assertEquals(north.extent.left, extent.left, 1e-6f)
        assertEquals(north.extent.bottom, extent.bottom, 1e-6f)
    }
}
