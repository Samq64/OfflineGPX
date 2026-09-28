package dev.samuelq.gpx.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedWindowTest {

    @Test
    fun `says nothing until the window has span`() {
        val window = SpeedWindow()
        assertNull(window.speedMps)
        window.add(0.0, 0.0)
        assertNull(window.speedMps)
        window.add(1.0, 10.0)
        // One second in, the quotient is just whatever the last hop was.
        assertNull(window.speedMps)
    }

    @Test
    fun `averages over the window rather than the last hop`() {
        val window = SpeedWindow()
        // A steady 5 m/s with a single wild sample in the middle of it.
        repeat(11) { second ->
            val distance = second * 5.0 + if (second == 5) 30.0 else 0.0
            window.add(second.toDouble(), distance)
        }
        val speed = assertNotNull(window.speedMps)
        assertTrue(speed in 4.5..5.5, "got $speed, expected about 5")
    }

    /**
     * The reason it is fed on every reading and not every recorded point: when the ride
     * stops, distance stops growing but time does not, so the number has to come down.
     */
    @Test
    fun `decays to zero when distance stops growing`() {
        val window = SpeedWindow()
        repeat(10) { window.add(it.toDouble(), it * 5.0) }
        assertTrue(assertNotNull(window.speedMps) > 4.0)

        for (second in 10..30) window.add(second.toDouble(), 45.0)
        assertEquals(0.0, assertNotNull(window.speedMps))
    }

    @Test
    fun `keeps at least the window's worth of samples`() {
        val window = SpeedWindow()
        repeat(100) { window.add(it.toDouble(), it * 2.0) }
        // Steady 2 m/s throughout, so however it trims, the answer is 2.
        assertEquals(2.0, assertNotNull(window.speedMps), 0.001)
    }

    @Test
    fun `a replayed out-of-order reading is ignored`() {
        val window = SpeedWindow()
        repeat(10) { window.add(it.toDouble(), it * 3.0) }
        val before = assertNotNull(window.speedMps)
        window.add(2.0, 500.0)
        assertEquals(before, assertNotNull(window.speedMps))
    }

    @Test
    fun `reset clears the window`() {
        val window = SpeedWindow()
        repeat(10) { window.add(it.toDouble(), it * 3.0) }
        window.reset()
        assertNull(window.speedMps)
    }
}
