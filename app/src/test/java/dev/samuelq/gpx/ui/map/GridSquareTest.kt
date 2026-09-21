package dev.samuelq.gpx.ui.map

import dev.samuelq.gpx.core.model.UnitSystem
import kotlin.math.floor
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grid's side of the split from the scale bar: a square is still a round distance, but
 * it is sized by its own target and stays usable at every zoom without a clamp.
 *
 * Sizes here are of the *pattern tile*. MapLibre draws it stretched by `2^frac(zoom)`, so
 * what reaches the screen is the tile times that factor - which is why every assertion
 * about what the reader sees goes through [rendered].
 */
class GridSquareTest {

    private val fallback = 48
    private val target = 64f

    /** An integer zoom, where the renderer draws the tile at its own size. */
    private val whole = 12.0

    /** What the reader actually sees, tile times the renderer's stretch. */
    private fun rendered(square: Int, zoom: Double): Double =
        square * 2.0.pow(zoom - floor(zoom))

    private fun squareAt(
        metersPerPixel: Double,
        zoom: Double = whole,
        targetPx: Float = target,
        units: UnitSystem = UnitSystem.METRIC,
    ) = gridSquarePx(metersPerPixel, zoom, targetPx, units, fallback)

    @Test
    fun `square is a round number of metres across`() {
        // 2 m per pixel, 64 px of target: 128 m of ground wanted, 100 m the round answer.
        val square = squareAt(metersPerPixel = 2.0)
        assertEquals(50, square)
        assertEquals(100.0, square * 2.0)
    }

    @Test
    fun `square is a round number of feet or miles under imperial`() {
        // Under a mile the bar and the grid both speak feet, so the grid does too.
        val feet = squareAt(metersPerPixel = 1.0, units = UnitSystem.IMPERIAL)
        assertEquals(METERS_PER_FOOT * 200, feet.toDouble(), 0.5)

        val miles = squareAt(metersPerPixel = 40.0, units = UnitSystem.IMPERIAL)
        assertEquals(METERS_PER_MILE / 40.0, miles.toDouble(), 0.5)
    }

    @Test
    fun `square stays near its target at every zoom, without a clamp`() {
        // The 1-2-5 ladder never has a gap wider than 2.5x, so the snapped square is always
        // between about 40% and 100% of what was asked for - from centimetres per pixel to
        // tens of kilometres per pixel.
        var metersPerPixel = 0.01
        while (metersPerPixel < 100_000.0) {
            for (units in UnitSystem.entries) {
                val square = squareAt(metersPerPixel, units = units)
                assertTrue(square in 25..64, "$square px at $metersPerPixel m/px in $units")
            }
            metersPerPixel *= 1.37
        }
    }

    @Test
    fun `an unknown scale falls back rather than inventing one`() {
        assertEquals(fallback, squareAt(0.0))
        assertEquals(fallback, squareAt(-1.0))
        assertEquals(fallback, squareAt(Double.NaN))
        assertEquals(fallback, squareAt(Double.POSITIVE_INFINITY))
        assertEquals(fallback, squareAt(2.0, targetPx = 0f))
    }

    @Test
    fun `the target decides the size, and nothing else does`() {
        // The whole point of the decoupling: change the grid's target and only the grid
        // moves. Doubling the target buys a square between one and two ladder rungs bigger.
        val small = squareAt(2.0, targetPx = 32f)
        val large = squareAt(2.0, targetPx = 128f)
        assertTrue(large > small, "$large should exceed $small")
    }

    /**
     * The correction that makes the grid worth counting across. Between integer zooms the
     * renderer stretches the pattern by up to a factor of two; the tile shrinks to match,
     * so the square on screen keeps measuring the distance it claims.
     */
    @Test
    fun `a square measures the same distance at every zoom, not just whole ones`() {
        val metersPerPixel = 2.0
        // 100 m at 2 m/px is a 50 px square, whatever the camera is doing.
        var zoom = 12.0
        while (zoom < 13.0) {
            val square = squareAt(metersPerPixel, zoom = zoom)
            val onScreen = rendered(square, zoom)
            // Within the quantisation of the correction - eight steps to the octave.
            assertEquals(50.0, onScreen, 50.0 * MAX_STRETCH_ERROR, "at zoom $zoom")
            zoom += 0.05
        }
    }

    @Test
    fun `an unknown zoom draws the tile unstretched rather than refusing`() {
        assertEquals(squareAt(2.0, zoom = 12.0), squareAt(2.0, zoom = Double.NaN))
    }

    private companion object {
        const val METERS_PER_FOOT = 0.3048
        const val METERS_PER_MILE = 1609.344

        /** Half a step of the eight the octave is corrected in: 2^(1/16) - 1. */
        const val MAX_STRETCH_ERROR = 0.05
    }
}
