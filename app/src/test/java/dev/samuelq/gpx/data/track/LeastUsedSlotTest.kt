package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.ColorUse
import kotlin.test.Test
import kotlin.test.assertEquals

class LeastUsedSlotTest {

    private fun shown(vararg slots: Int) = slots.map { ColorUse(it, visible = true) }
    private fun hidden(vararg slots: Int) = slots.map { ColorUse(it, visible = false) }

    @Test
    fun `an empty library starts at the first slot`() {
        assertEquals(0, leastUsedSlot(emptyList(), 6))
    }

    @Test
    fun `a slot freed by a delete is reused before any doubles up`() {
        assertEquals(0, leastUsedSlot(shown(1, 2, 3, 4, 5), 6))
    }

    @Test
    fun `slots on the map count before hidden ones`() {
        assertEquals(1, leastUsedSlot(shown(0, 2, 3, 4, 5) + hidden(1, 1), 6))
    }

    @Test
    fun `among equally shown slots the less used overall wins`() {
        assertEquals(2, leastUsedSlot(shown(0, 1, 2, 3, 4, 5) + hidden(0, 1, 3, 4, 5), 6))
    }

    @Test
    fun `slots past the assignable ones are neither picked nor counted`() {
        assertEquals(0, leastUsedSlot(shown(1, 2, 3, 4, 5, 6, 6), 6))
    }
}
