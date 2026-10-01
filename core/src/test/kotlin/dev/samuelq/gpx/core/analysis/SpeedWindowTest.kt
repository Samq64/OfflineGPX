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
        // One second in, that is just the last hop.
        assertNull(window.speedMps)
    }

    @Test
    fun `averages over the window rather than the last hop`() {
        val window = SpeedWindow()
        repeat(11) { second ->
            val distance = second * 5.0 + if (second == 5) 30.0 else 0.0
            window.add(second.toDouble(), distance)
        }
        val speed = assertNotNull(window.speedMps)
        assertTrue(speed in 4.5..5.5, "got $speed, expected about 5")
    }

    /** Why it is fed every reading, not every recorded point. */
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
