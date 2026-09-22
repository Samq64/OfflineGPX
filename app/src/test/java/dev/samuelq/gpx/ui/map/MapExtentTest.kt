package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.map.MapFileHeader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the camera is fitted to and penned inside of.
 *
 * The extent is now assembled from each route's own remembered box rather than from every
 * position on the map, so these check that the box is the same one - including at the two
 * edges where "there is nothing to frame" has to keep meaning that.
 */
class MapExtentTest {

    private fun route(vararg positions: Pair<Double, Double>, id: Long = 1) = RouteOverlay(
        trackId = id,
        points = positions.map { (lat, lon) -> TrackPoint(lat, lon, null, null) },
        segmentStartIndices = intArrayOf(0),
        color = Color.Red,
    )

    private fun basemap(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ) = OfflineMap(
        file = File("test.map"),
        header = MapFileHeader(
            minZoom = 0,
            maxZoom = 14,
            minLongitude = west,
            minLatitude = south,
            maxLongitude = east,
            maxLatitude = north,
            tileSize = 256,
            attribution = null,
        ),
        sizeBytes = 0,
    )

    @Test
    fun `a route's box is the extremes of its positions`() {
        val bounds = assertNotNull(route(1.0 to 5.0, -2.0 to 9.0, 0.5 to 7.0).bounds)
        assertEquals(-2.0, bounds.southLatitude)
        assertEquals(1.0, bounds.northLatitude)
        assertEquals(5.0, bounds.westLongitude)
        assertEquals(9.0, bounds.eastLongitude)
    }

    @Test
    fun `a route with no positions has no box`() {
        assertNull(route().bounds)
    }

    @Test
    fun `the extent spans every route, the recording and every map`() {
        val extent = assertNotNull(
            extentOf(
                routes = listOf(route(1.0 to 1.0, 2.0 to 2.0), route(-5.0 to 3.0, 0.0 to 4.0, id = 2)),
                liveRoute = route(7.0 to -1.0, 7.5 to -0.5, id = 3),
                basemaps = listOf(basemap(south = -6.0, west = -2.0, north = 3.0, east = 6.0)),
            )
        )
        assertEquals(-6.0, extent.minLatitude)
        assertEquals(7.5, extent.maxLatitude)
        assertEquals(-2.0, extent.minLongitude)
        assertEquals(6.0, extent.maxLongitude)
    }

    @Test
    fun `the recording alone is enough to frame`() {
        val extent = assertNotNull(
            extentOf(routes = emptyList(), liveRoute = route(1.0 to 1.0, 2.0 to 2.0), basemaps = emptyList())
        )
        assertEquals(1.0, extent.minLatitude)
        assertEquals(2.0, extent.maxLatitude)
    }

    @Test
    fun `nothing to look at is not a box`() {
        assertNull(extentOf(emptyList(), null, emptyList()))
        assertNull(extentOf(listOf(route()), null, emptyList()))
    }

    /**
     * The first fix of a recording, with no map imported. A point is not an area: fitting
     * to it means an arbitrary zoom, and penning the camera inside it means a map that
     * cannot be panned at all.
     */
    @Test
    fun `a single position is not a box`() {
        assertNull(extentOf(routes = emptyList(), liveRoute = route(51.5 to -0.1), basemaps = emptyList()))
        assertNull(extentOf(routes = listOf(route(51.5 to -0.1)), liveRoute = null, basemaps = emptyList()))
    }

    /** Two tracks that each stood still, in two different places, are still an area. */
    @Test
    fun `two separate single positions are a box`() {
        val extent = assertNotNull(
            extentOf(
                routes = listOf(route(51.5 to -0.1), route(52.0 to 0.2, id = 2)),
                liveRoute = null,
                basemaps = emptyList(),
            )
        )
        assertEquals(51.5, extent.minLatitude)
        assertEquals(52.0, extent.maxLatitude)
    }
}
