package dev.samuelq.gpx.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseHexTest {
    @Test
    fun `takes six digits with or without a hash, as opaque`() {
        assertEquals(0xFF2773EE.toInt(), parseHex("#2773ee"))
        assertEquals(0xFF2773EE.toInt(), parseHex(" 2773EE "))
    }

    @Test
    fun `rejects anything else`() {
        listOf("", "#", "#2773e", "#2773eeff", "#zz73ee").forEach { assertNull(it, parseHex(it)) }
    }
}
