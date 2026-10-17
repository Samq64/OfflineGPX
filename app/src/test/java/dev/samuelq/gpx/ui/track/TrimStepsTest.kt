package dev.samuelq.gpx.ui.track

import kotlin.test.Test
import kotlin.test.assertEquals

class TrimStepsTest {

    @Test
    fun `a hold steps a point at a time for its first second`() {
        assertEquals(listOf(1, 1, 1), listOf(0L, 500L, 950L).map(::holdSteps))
    }

    @Test
    fun `then doubles every half second, up to a cap`() {
        assertEquals(listOf(2, 4, 8, 64), listOf(1000L, 1500L, 2000L, 3500L).map(::holdSteps))
        assertEquals(64, holdSteps(60_000L))
    }
}
