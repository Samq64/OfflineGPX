package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.data.map.mapFile
import dev.samuelq.gpx.data.map.offlineMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The extent is built from each route's cached box, so check it matches the positions. */
class MapExtentTest {

    private fun route(vararg positions: Pair<Double, Double>, id: Long = 1) = RouteOverlay(
        trackId = id,
        points = TrackPoints.of(positions.map { (lat, lon) -> TrackPoint(lat, lon, null, null) }),
        color = Color.Red,
    )

    private fun basemap(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ) = offlineMap(mapFile(south, west, north, east, baseZoom = 2))

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
        // Tracks get 5% of their span each side; the map's edges stay where they are.
        assertEquals(-6.0, extent.minLatitude, 1e-6)
        assertEquals(7.525, extent.maxLatitude, 1e-6)
        assertEquals(-2.0, extent.minLongitude, 1e-6)
        assertEquals(6.0, extent.maxLongitude, 1e-6)
    }

    @Test
    fun `the recording alone is enough to frame`() {
        val extent = assertNotNull(
            extentOf(routes = emptyList(), liveRoute = route(1.0 to 1.0, 2.0 to 2.0), basemaps = emptyList())
        )
        assertEquals(0.95, extent.minLatitude, 1e-6)
        assertEquals(2.05, extent.maxLatitude, 1e-6)
    }

    @Test
    fun `nothing to look at is not a box`() {
        assertNull(extentOf(emptyList(), null, emptyList()))
        assertNull(extentOf(listOf(route()), null, emptyList()))
    }

    /** Fitting to a point means an arbitrary zoom and a camera that cannot pan. */
    @Test
    fun `a single position is not a box`() {
        assertNull(extentOf(routes = emptyList(), liveRoute = route(51.5 to -0.1), basemaps = emptyList()))
        assertNull(extentOf(routes = listOf(route(51.5 to -0.1)), liveRoute = null, basemaps = emptyList()))
    }

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

    @Test
    fun `a loaded track's overlay draws its profile's points, with known bounds unmeasured`() {
        val points = TrackPoints.of(listOf(TrackPoint(1.0, 1.0, null, null), TrackPoint(2.0, 2.0, null, null)))
        val track = dev.samuelq.gpx.core.model.Track(name = null, points = points)
        val loaded = dev.samuelq.gpx.data.track.LoadedTrack(
            id = 9, displayName = "a.gpx", track = track,
            profile = dev.samuelq.gpx.core.analysis.TrackAnalyzer.analyze(track),
        )
        val known = dev.samuelq.gpx.core.model.GeoBounds(0.0, 0.0, 5.0, 5.0)

        val overlay = loaded.toOverlay(Color.Blue, known)
        assertEquals(9L, overlay.trackId)
        assertEquals(2, overlay.points.size)
        assertEquals(known, overlay.bounds)
        assertEquals(2.0, assertNotNull(loaded.toOverlay(Color.Blue).bounds).northLatitude)
    }
}
