package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MapMarkersTest {

    /** About 11 m per step of latitude near the equator. */
    private fun route(vararg segments: List<Pair<Double, Double>>) = RouteOverlay(
        trackId = 1,
        points = TrackPoints.of(
            *segments.map { s ->
                s.map { (lat, lon) -> TrackPoint(lat, lon, null, null) }
            }.toTypedArray(),
        ),
        color = Color.Red,
    )

    @Test
    fun `the heading is from the last point far enough back`() {
        assertEquals(0.0, assertNotNull(route(listOf(0.0 to 0.0, 0.0001 to 0.0)).headingDegrees()), 0.01)
        // The point before last is ~1 m off, inside GPS jitter, so the one before it is used.
        val jittered = route(listOf(0.0 to 0.0, 0.0001 to 0.0, 0.0001 to 0.00001)).headingDegrees()
        assertEquals(5.71, assertNotNull(jittered), 0.01)
    }

    @Test
    fun `a heading never reaches back across a gap`() {
        assertNull(route(listOf(0.0 to 0.0, 0.0001 to 0.0), listOf(0.0002 to 0.0, 0.0002 to 0.00001)).headingDegrees())
    }

    @Test
    fun `one point or none has no heading`() {
        assertNull(route(listOf(0.0 to 0.0)).headingDegrees())
        assertNull(route().headingDegrees())
    }
}
