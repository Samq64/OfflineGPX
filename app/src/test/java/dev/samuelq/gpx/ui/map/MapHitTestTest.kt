package dev.samuelq.gpx.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.oscim.core.MercatorProjection

/** Which point a tap on a line picks, in map pixels around the origin. */
class MapHitTestTest {

    private val mapSize = 256.0 * (1 shl 20)
    private val centre = mapSize / 2

    private fun at(x: Double, y: Double) = TrackPoint(
        MercatorProjection.toLatitude((centre + y) / mapSize),
        MercatorProjection.toLongitude((centre + x) / mapSize),
    )

    /** Each list of x positions, all at y = 0, is a segment. */
    private fun route(vararg segments: List<Double>, id: Long = 1) = RouteOverlay(
        trackId = id,
        points = TrackPoints.of(*segments.map { xs -> xs.map { at(it, 0.0) } }.toTypedArray()),
        color = Color.Red,
    )

    private fun tap(x: Double, y: Double, vararg routes: RouteOverlay, reach: Float = 10f) =
        nearestOnRoutes(centre + x, centre + y, mapSize, routes.toList(), reach)

    @Test
    fun `a lone point is hit within reach`() {
        val lone = route(listOf(0.0))
        assertEquals(1L to 0, tap(3.0, 4.0, lone))
        assertNull(tap(20.0, 0.0, lone))
    }

    @Test
    fun `a segment picks its nearer end`() {
        val line = route(listOf(0.0, 100.0))
        assertEquals(1L to 0, tap(30.0, 5.0, line))
        assertEquals(1L to 1, tap(70.0, 5.0, line))
        assertEquals(1L to 0, tap(-5.0, 0.0, line))
        assertEquals(1L to 1, tap(105.0, 0.0, line))
        assertNull(tap(50.0, 15.0, line))
    }

    @Test
    fun `indices run on across segments`() {
        val line = route(listOf(0.0, 100.0), listOf(200.0, 300.0, 400.0))
        assertEquals(1L to 3, tap(290.0, 2.0, line))
        assertEquals(1L to 2, tap(195.0, 0.0, line))
    }

    @Test
    fun `a break between segments is not a line`() {
        val line = route(listOf(0.0, 100.0), listOf(200.0, 300.0))
        assertNull(tap(150.0, 0.0, line))
    }

    @Test
    fun `a lone point after a segment keeps its index`() {
        val line = route(listOf(0.0, 100.0), listOf(200.0))
        assertEquals(1L to 2, tap(200.0, 3.0, line))
    }

    @Test
    fun `the nearest route wins`() {
        val near = route(listOf(0.0, 100.0), id = 1)
        val far = route(listOf(0.0, 100.0).map { it + 2 }, id = 2)
        assertEquals(1L to 0, tap(-3.0, 0.0, far, near))
        assertEquals(2L to 1, tap(105.0, 0.0, near, far))
    }

    /** Pins drawn with their tip at the waypoint, here on screen as given. */
    private fun pickPin(x: Float, y: Float, vararg pins: Pair<Waypoint, Offset>, onTop: Waypoint? = null) =
        pickWaypoint(
            x,
            y,
            { point -> pins.first { it.first.point == point }.second },
            pins.map { it.first },
            headRadiusPx = 10f,
            tipLengthPx = 30f,
            minHalfPx = 24f,
            onTop = onTop,
        )

    private val a = Waypoint(TrackPoint(1.0, 1.0), "A")
    private val b = Waypoint(TrackPoint(2.0, 2.0), "B")

    @Test
    fun `a pin is hit over its head and tip, padded to a finger`() {
        val pin = a to Offset(100f, 100f)
        assertEquals(a, pickPin(100f, 70f, pin))
        // Padded: 24 px each side, and 4 px below the tip and above the head.
        assertEquals(a, pickPin(123f, 70f, pin))
        assertEquals(a, pickPin(100f, 103f, pin))
        assertEquals(a, pickPin(100f, 57f, pin))
        assertNull(pickPin(125f, 70f, pin))
        assertNull(pickPin(100f, 105f, pin))
        assertNull(pickPin(100f, 55f, pin))
    }

    @Test
    fun `overlapping pins go to the one on top, else the nearest head`() {
        val pins = arrayOf(a to Offset(100f, 100f), b to Offset(110f, 100f))
        assertEquals(b, pickPin(108f, 70f, *pins))
        assertEquals(a, pickPin(102f, 70f, *pins))
        assertEquals(a, pickPin(108f, 70f, *pins, onTop = a))
        assertNull(pickPin(500f, 500f, *pins))
    }
}
