package dev.samuelq.gpx.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import org.oscim.layers.vector.geometries.LineDrawable

class MapLayersTest {

    private val styles = RouteStyles(Density(1f))

    private fun route(id: Long, vararg segments: List<Pair<Double, Double>>) = RouteOverlay(
        trackId = id,
        points = TrackPoints.of(
            *segments.map { s ->
                s.map { (lat, lon) -> TrackPoint(lat, lon, null, null) }
            }.toTypedArray(),
        ),
        color = Color.Red,
    )

    private val first = route(
        1,
        listOf(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0),
        listOf(7.0 to 8.0),
        listOf(
            9.0 to 10.0,
            11.0 to 12.0,
        ),
    )
    private val second = route(2, listOf(0.0 to 0.0, 1.0 to 1.0))

    private val LineDrawable.lonLat get() = geometry.coordinates.flatMap { listOf(it.x, it.y) }
    private val LineDrawable.dimmed get() = Color(style.strokeColor).alpha < 1f

    @Test
    fun `each segment of two or more points is a line of its own, in lon-lat`() {
        val lines = listOf(first).toLines(styles)
        assertEquals(
            listOf(listOf(2.0, 1.0, 4.0, 3.0, 6.0, 5.0), listOf(10.0, 9.0, 12.0, 11.0)),
            lines.map { it.lonLat },
        )
    }

    @Test
    fun `routes stack in list order with none dimmed`() {
        val lines = listOf(first, second).toLines(styles)
        assertEquals(listOf(0, 0, 1), lines.map { it.priority })
        assertEquals(listOf(false, false, false), lines.map { it.dimmed })
    }

    @Test
    fun `the focused route goes on top and the rest are dimmed`() {
        val lines = listOf(second, first).toLines(styles, focusedId = 2)
        assertEquals(listOf(2, 1, 1), lines.map { it.priority })
        assertEquals(listOf(false, true, true), lines.map { it.dimmed })
    }

    @Test
    fun `focus on a route not shown dims nothing, and on the recording dims everything`() {
        assertEquals(
            listOf(false, false, false),
            listOf(first, second).toLines(styles, focusedId = 99).map {
                it.dimmed
            },
        )
        assertEquals(
            listOf(true, true, true),
            listOf(first, second).toLines(styles, focusedId = LIVE_TRACK_ID).map { it.dimmed },
        )
    }
}
