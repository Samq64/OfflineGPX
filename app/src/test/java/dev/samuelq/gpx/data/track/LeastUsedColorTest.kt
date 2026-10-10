package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.ColorUse
import dev.samuelq.gpx.data.track.RouteColor.Blue
import dev.samuelq.gpx.data.track.RouteColor.Cyan
import dev.samuelq.gpx.data.track.RouteColor.Green
import dev.samuelq.gpx.data.track.RouteColor.Magenta
import dev.samuelq.gpx.data.track.RouteColor.Red
import dev.samuelq.gpx.data.track.RouteColor.Yellow
import kotlin.test.Test
import kotlin.test.assertEquals

class LeastUsedColorTest {

    private fun shown(vararg colors: RouteColor) = colors.map { ColorUse(it, visible = true) }
    private fun hidden(vararg colors: RouteColor) = colors.map { ColorUse(it, visible = false) }

    @Test
    fun `an empty library starts at the first colour`() {
        assertEquals(Red, leastUsed(emptyList()))
    }

    @Test
    fun `a colour freed by a delete is reused before any doubles up`() {
        assertEquals(Red, leastUsed(shown(Yellow, Green, Cyan, Blue, Magenta)))
    }

    @Test
    fun `colours on the map count before hidden ones`() {
        assertEquals(Yellow, leastUsed(shown(Red, Green, Cyan, Blue, Magenta) + hidden(Yellow, Yellow)))
    }

    @Test
    fun `among equally shown colours the less used overall wins`() {
        assertEquals(
            Green,
            leastUsed(shown(Red, Yellow, Green, Cyan, Blue, Magenta) + hidden(Red, Yellow, Cyan, Blue, Magenta)),
        )
    }
}
