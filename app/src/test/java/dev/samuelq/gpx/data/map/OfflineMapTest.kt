package dev.samuelq.gpx.data.map

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `andorra-fragment.map` is a real mapsforge extract truncated to its header, which is all
 * that's read; it's padded back to its declared size, which the reader checks.
 */
class OfflineMapTest {

    private fun fixture(): File {
        val bytes = javaClass.getResource("/andorra-fragment.map")!!.readBytes()
        // After the magic, header length and file version.
        val declaredSize = ByteBuffer.wrap(bytes, 28, 8).long
        return File.createTempFile("andorra", ".map").apply {
            deleteOnExit()
            writeBytes(bytes)
            RandomAccessFile(this, "rw").use { it.setLength(declaredSize) }
        }
    }

    @Test
    fun `reads a real extract`() {
        val bounds = assertNotNull(OfflineMap.read(fixture())).bounds

        assertEquals(42.535, bounds.minLatitude)
        assertEquals(1.520, bounds.minLongitude)
        assertEquals(42.550, bounds.maxLatitude)
        assertEquals(1.545, bounds.maxLongitude)
    }

    /** Not the z21 it claims: z15 to z21 are the z14 tile scaled up. */
    @Test
    fun `the base zoom is the deepest stored`() {
        assertEquals(14, assertNotNull(OfflineMap.read(fixture())).baseZoom)
    }

    @Test
    fun `locates the deepest sub-file`() {
        val map = assertNotNull(OfflineMap.read(fixture()))
        assertEquals(14, map.deepest.baseZoomLevel.toInt())
        assertEquals(171_810L, map.deepest.startAddress)
        assertEquals(144_339L, map.deepest.subFileSize)
        assertEquals(10, map.subFileFor(9).baseZoomLevel.toInt())
        assertEquals(14, map.subFileFor(25).baseZoomLevel.toInt())
    }

    /** Where an extract's ODbL credit lives. */
    @Test
    fun `attribution comes from the comment`() {
        assertEquals("Map data (c) OpenStreetMap contributors", OfflineMap.read(fixture())?.attribution)
    }

    @Test
    fun `falls back to created-by when there is no comment`() {
        assertEquals("osmosis", offlineMap(mapFile(comment = null, createdBy = "osmosis")).attribution)
        assertEquals("osmosis", offlineMap(mapFile(comment = "   ", createdBy = "osmosis")).attribution)
        assertNull(offlineMap(mapFile(comment = null, createdBy = null)).attribution)
    }

    @Test
    fun `a file that is not a map is not a map`() {
        val notAMap = File.createTempFile("not-a", ".map").apply {
            writeBytes(ByteArray(4096) { 0x7F })
            deleteOnExit()
        }
        assertNull(OfflineMap.read(notAMap))
    }

    @Test
    fun `a file shorter than it says is refused`() {
        val truncated = File.createTempFile("truncated", ".map").apply {
            writeBytes(fixture().readBytes().copyOf(64))
            deleteOnExit()
        }
        assertNull(OfflineMap.read(truncated))
        val bytes = javaClass.getResource("/andorra-fragment.map")!!.readBytes()
        val unpadded = File.createTempFile("unpadded", ".map").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        assertNull(OfflineMap.read(unpadded))
    }

    /** Debug files interleave 32-byte signatures through the data. */
    @Test
    fun `a debug build is refused`() {
        assertNull(OfflineMap.read(mapFile(debug = true)))
    }

    @Test
    fun `an inverted bounding box is refused`() {
        assertNull(OfflineMap.read(mapFile(south = 50.0, north = 40.0)))
    }

    /** String lengths are VBE-U, not the 2-byte short used elsewhere in the header. */
    @Test
    fun `reads past a multi-byte string length`() {
        val map = offlineMap(mapFile(wayTags = List(4) { "k=" + "v".repeat(200) }))
        assertEquals(42.0, map.bounds.minLatitude)
        assertEquals(14, map.baseZoom)
    }

    @Test
    fun `named by its file, and kept when moved`() {
        val map = offlineMap(mapFile())
        val moved = File(map.file.parentFile, "Andorra.map")
        assertEquals(map.file.nameWithoutExtension, map.displayName)
        val after = map.movedTo(moved)
        assertEquals("Andorra", after.displayName)
        assertEquals(map.bounds, after.bounds)
        assertEquals(map.sizeBytes, after.sizeBytes)
    }

    /** Past the stored tiles by a few levels, never past VTM's limit. */
    @Test
    fun `the camera stops a few zooms past the deepest tiles`() {
        assertEquals(18, offlineMap(mapFile(baseZoom = 14)).maxViewZoom)
        assertEquals(org.oscim.map.Viewport.MAX_ZOOM_LEVEL, offlineMap(mapFile(north = 42.01, east = 1.01, baseZoom = 19)).maxViewZoom)
    }

    @Test
    fun `a missing file is not a map`() {
        assertNull(OfflineMap.read(File("/nonexistent/gone.map")))
    }
}
