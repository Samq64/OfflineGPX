package dev.samuelq.gpx.data.map

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.samuelq.gpx.container
import dev.samuelq.gpx.data.track.TrackFiles
import dev.samuelq.gpx.targetContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.oscim.tiling.source.mapfile.header.MapFileHeader

@RunWith(AndroidJUnit4::class)
class MapStoreTest {

    /** Through a content URI, whose provider reports the size the import reserves room for. */
    @Test
    fun aSizedFileGetsRoomAndIsThenRead() {
        val file = File(TrackFiles.dir(targetContext), "not-a-map-${System.nanoTime()}.map")
        file.writeBytes(ByteArray(64 * 1024) { it.toByte() })
        val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
        try {
            val result = runBlocking { container.mapStore.import(uri) }
            assertEquals(MapImportError.NOT_A_MAP_FILE, assertIs<MapImportResult.Failed>(result).error)
        } finally {
            file.delete()
        }
    }

    @Test
    fun exportCopiesTheFile() {
        val dir = targetContext.cacheDir
        val source = File(dir, "source-${System.nanoTime()}.map").apply {
            writeBytes(ByteArray(10_000) { it.toByte() })
        }
        // Longer, so a copy that didn't truncate would leave a tail.
        val target = File(dir, "export-${System.nanoTime()}.map").apply { writeBytes(ByteArray(20_000)) }
        try {
            val map = OfflineMap(source, MapFileHeader(), source.length())
            assertTrue(runBlocking { container.mapStore.export(map, Uri.fromFile(target)) })
            assertTrue(source.readBytes().contentEquals(target.readBytes()))
        } finally {
            source.delete()
            target.delete()
        }
    }
}
