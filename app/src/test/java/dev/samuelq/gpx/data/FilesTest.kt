package dev.samuelq.gpx.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {

    private val dir = Files.createTempDirectory("files").toFile().apply { deleteOnExit() }

    @Test
    fun `uniqueFile numbers past files that exist`() {
        File(dir, "ride.gpx").createNewFile()
        assertEquals(File(dir, "ride (2).gpx"), uniqueFile(dir, "ride.gpx", "gpx", "track"))
        assertEquals(File(dir, "track.gpx"), uniqueFile(dir, null, "gpx", "track"))
    }

    @Test
    fun `an atomic write replaces the file`() {
        val file = File(dir, "a.txt").apply { writeText("old") }
        writeAtomically(file) { it.write("new".toByteArray()) }
        assertEquals("new", file.readText())
    }

    @Test
    fun `a failed atomic write leaves the old file`() {
        val file = File(dir, "a.txt").apply { writeText("old") }
        assertFailsWith<IOException> {
            writeAtomically(file) {
                it.write("half".toByteArray())
                throw IOException("disk full")
            }
        }
        assertEquals("old", file.readText())
        assertEquals(listOf("a.txt"), dir.list()!!.toList(), "no temporary file left")
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `sameBytes compares content over several chunks`() {
        val big = ByteArray(20_000) { (it % 251).toByte() }
        assertTrue(sameBytes(file("a", big), file("b", big.copyOf())))
        assertTrue(sameBytes(file("e1", ByteArray(0)), file("e2", ByteArray(0))))

        val lastDiffers = big.copyOf().also { it[it.lastIndex]++ }
        assertFalse(sameBytes(file("a", big), file("c", lastDiffers)))
        val firstDiffers = big.copyOf().also { it[0]++ }
        assertFalse(sameBytes(file("a", big), file("d", firstDiffers)))
        assertFalse(sameBytes(file("a", big), file("short", big.copyOf(100))))
    }

    /** Equal lengths, but one file grows while read: the chunk counts differ. */
    @Test
    fun `sameBytes is false when a read comes up short`() {
        val a = file("a", ByteArray(10_000))
        val b = object : File(dir, "b") {
            override fun length() = 10_000L
        }
        b.writeBytes(ByteArray(5_000))
        assertFalse(sameBytes(a, b))
    }
}
