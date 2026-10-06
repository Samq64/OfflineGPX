package dev.samuelq.gpx.data.map

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.samuelq.gpx.container
import dev.samuelq.gpx.data.track.TrackFiles
import dev.samuelq.gpx.targetContext
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class MapStoreTest {

    /** Through a content URI, whose provider reports the size the import reserves room for. */
    @Test
    fun aSizedFileGetsRoomAndIsThenRead() {
        val file = File(TrackFiles.importsDir(targetContext), "not-a-map-${System.nanoTime()}.map")
        file.writeBytes(ByteArray(64 * 1024) { it.toByte() })
        val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
        try {
            val result = runBlocking { container.mapStore.import(uri) }
            assertEquals(MapImportError.NOT_A_MAP_FILE, assertIs<MapImportResult.Failed>(result).error)
        } finally {
            file.delete()
        }
    }
}
