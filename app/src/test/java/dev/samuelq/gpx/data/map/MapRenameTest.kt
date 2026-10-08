package dev.samuelq.gpx.data.map

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapRenameTest {

    private val dir = createTempDirectory("maps").toFile().apply { deleteOnExit() }

    private fun file(name: String) = File(dir, name).apply {
        writeText(name)
        setLastModified(1_000_000_000_000)
    }

    @Test
    fun `renames in place and keeps the mtime`() {
        val renamed = renamed(file("a.map"), "Andorra")
        assertEquals(File(dir, "Andorra.map"), renamed)
        assertEquals(1_000_000_000_000, renamed?.lastModified())
        assertEquals("a.map", renamed?.readText())
    }

    @Test
    fun `numbers a name another map has`() {
        file("Andorra.map")
        assertEquals("Andorra (2).map", renamed(file("a.map"), "Andorra")?.name)
    }

    @Test
    fun `the same name is no change`() {
        val file = file("Andorra.map")
        assertEquals(file, renamed(file, "Andorra.map"))
        assertTrue(file.exists())
    }
}
